"""离线提色工具回归测试，只使用临时合成资源，不读取/运行客户端。"""
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from zipfile import ZipFile

from PIL import Image

TOOLS = runpy.run_path(str(Path(__file__).resolve().parents[1] / "extract_vanilla_colors.py"))
Extractor = TOOLS["Extractor"]
resource_path = TOOLS["resource_path"]
pixel_average = TOOLS["pixel_average"]
animation_frames = TOOLS["animation_frames"]


class ExtractVanillaColorsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write_json(self, path, value):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(value), encoding="utf-8")

    def texture(self, name, pixels, size):
        file = self.root / resource_path("test:" + name, "textures", ".png")
        file.parent.mkdir(parents=True, exist_ok=True)
        image = Image.new("RGBA", size)
        image.putdata(pixels)
        image.save(file)

    def model(self, name, value):
        self.write_json(resource_path("test:" + name, "models"), value)

    def state(self, name, value):
        self.write_json(resource_path(name, "blockstates"), value)

    def test_transparent_white_does_not_bleach_cherry_color(self):
        image = Image.new("RGBA", (2, 1))
        image.putdata([(229, 172, 194, 255), (255, 255, 255, 0)])
        color, coverage = pixel_average([(image, 1)])
        self.assertEqual((229, 172, 194), color)
        self.assertEqual(.5, coverage)

    def test_alpha_weighting(self):
        image = Image.new("RGBA", (2, 1))
        image.putdata([(255, 0, 0, 255), (0, 0, 255, 85)])
        color, coverage = pixel_average([(image, 1)])
        self.assertEqual((191.25, 0, 63.75), color)

    def test_static_non_square_texture_uses_entire_image(self):
        image = Image.new("RGBA", (1, 2))
        image.putdata([(255, 0, 0, 255), (0, 0, 255, 255)])
        color, coverage = pixel_average(animation_frames(image, None))
        self.assertEqual((127.5, 0, 127.5), color)

    def test_animation_respects_frame_selection_and_duration(self):
        image = Image.new("RGBA", (1, 2))
        image.putdata([(255, 0, 0, 255), (0, 0, 255, 255)])
        frames = animation_frames(image, {"animation": {"frames": [{"index": 1, "time": 3}, 0]}})
        color, coverage = pixel_average(frames)
        self.assertEqual((63.75, 0, 191.25), color)

    def test_bad_animation_indices_are_rejected(self):
        image = Image.new("RGBA", (1, 2))
        with self.assertRaises(ValueError):
            animation_frames(image, {"animation": {"frames": [100]}})

    def test_uv_crop_uses_visible_region_only(self):
        image = Image.new("RGBA", (2, 1))
        image.putdata([(255, 0, 0, 255), (0, 0, 255, 255)])
        self.assertEqual((255, 0, 0), pixel_average([(image, 1)], (0, 0, 8, 16))[0])

    def test_inherited_texture_variables_and_sprite_objects(self):
        self.texture("red", [(200, 10, 20, 255)], (1, 1))
        self.model("parent", {"textures": {"face": "#base"}, "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"north": {"texture": "#face"}}}]})
        self.model("child", {"parent": "test:parent", "textures": {"base": {"sprite": "test:red", "force_translucent": True}}})
        self.state("stone", {"variants": {"": {"model": "test:child"}}})
        colors, records, missing = Extractor(self.root).generate({"STONE": "stone"})
        self.assertEqual((200, 10, 20), colors["STONE"])
        self.assertFalse(missing)
        self.assertEqual(["test:red"], records["STONE"]["textures"])

    def test_heavy_core_style_bare_variable_is_supported(self):
        extractor = Extractor(self.root)
        self.assertEqual("minecraft:block/heavy_core", extractor.resolve_texture("all", {"all": "minecraft:block/heavy_core"}))

    def test_parent_cycle_is_diagnosed_without_infinite_recursion(self):
        self.model("a", {"parent": "test:b"})
        self.model("b", {"parent": "test:a"})
        with self.assertRaisesRegex(ValueError, "parent"):
            Extractor(self.root).resolved_model("test:a")

    def test_texture_variable_cycle_is_diagnosed(self):
        with self.assertRaises(ValueError):
            Extractor(self.root).resolve_texture("#a", {"a": "#b", "b": "#a"})

    def test_area_weighting_and_model_arrays(self):
        self.texture("red", [(255, 0, 0, 255)], (1, 1))
        self.texture("blue", [(0, 0, 255, 255)], (1, 1))
        self.model("red", {"textures": {"particle": "test:red"}})
        self.model("blue", {"textures": {"particle": "test:blue"}})
        self.state("stone", {"variants": {"": [{"model": "test:red", "weight": 1}, {"model": "test:blue", "weight": 3}]}})
        colors, records, missing = Extractor(self.root).generate({"STONE": "stone"})
        self.assertEqual((63, 0, 191), colors["STONE"])

    def test_multipart_records_representative_not_exact_state(self):
        self.texture("red", [(200, 10, 20, 255)], (1, 1))
        self.model("red", {"textures": {"particle": "test:red"}})
        self.state("stone", {"multipart": [{"apply": {"model": "test:red"}}, {"when": {"north": "true"}, "apply": {"model": "test:red"}}]})
        colors, records, missing = Extractor(self.root).generate({"STONE": "stone"})
        self.assertEqual((200, 10, 20), colors["STONE"])
        self.assertTrue(any("multipart" in note for note in records["STONE"]["notes"]))

    def test_generic_leaf_tintindex_does_not_imply_cherry_foliage_tint(self):
        self.texture("pink", [(229, 172, 194, 255)], (1, 1))
        self.model("leaf", {"textures": {"all": "test:pink"}, "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"north": {"texture": "#all", "tintindex": 0}}}]})
        self.state("cherry_leaves", {"variants": {"": {"model": "test:leaf"}}})
        self.write_json("assets/minecraft/items/cherry_leaves.json", {"model": {"type": "minecraft:model", "model": "test:leaf"}})
        colors, records, missing = Extractor(self.root).generate({"CHERRY_LEAVES": "cherry_leaves"})
        self.assertEqual((229, 172, 194), colors["CHERRY_LEAVES"])

    def test_actual_item_constant_tint_is_applied_to_tinted_face_only(self):
        self.texture("gray", [(200, 200, 200, 255)], (1, 1))
        self.model("leaf", {"textures": {"all": "test:gray"}, "elements": [
            {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"north": {"texture": "#all", "tintindex": 0}}}]})
        self.state("oak_leaves", {"variants": {"": {"model": "test:leaf"}}})
        self.write_json("assets/minecraft/items/oak_leaves.json", {"model": {"type": "minecraft:model", "model": "test:leaf", "tints": [{"type": "minecraft:constant", "value": 0x00FF00}]}})
        colors, records, missing = Extractor(self.root).generate({"OAK_LEAVES": "oak_leaves"})
        self.assertEqual((0, 200, 0), colors["OAK_LEAVES"])

    def test_missing_resources_are_reported_not_filled_gray(self):
        self.state("stone", {"variants": {"": {"model": "test:missing"}}})
        colors, records, missing = Extractor(self.root).generate({"STONE": "stone"})
        self.assertFalse(colors)
        self.assertIn("STONE", missing)

    def test_resource_paths_cannot_leave_resource_root(self):
        for resource in ["test:../bad", "test:/absolute", "test:a/../../bad"]:
            with self.assertRaises(ValueError):
                resource_path(resource, "models")

    def test_archive_path_traversal_is_rejected(self):
        jar = self.root / "input.jar"
        with ZipFile(jar, "w") as archive:
            archive.writestr("assets/minecraft/models/../../outside.png", b"not an image")
        with self.assertRaises(ValueError):
            TOOLS["extract_resources"](jar, self.root / "extracted")

    def test_extractor_does_not_unpack_or_execute_classes(self):
        jar = self.root / "input.jar"
        with ZipFile(jar, "w") as archive:
            archive.writestr("Client.class", b"not executable")
            archive.writestr("assets/minecraft/blockstates/stone.json", b"{}")
        destination = self.root / "extracted"
        self.assertEqual(1, TOOLS["extract_resources"](jar, destination))
        self.assertFalse((destination / "Client.class").exists())
        with self.assertRaises(ValueError):
            TOOLS["extract_resources"](jar, destination)

    def test_generated_color_output_is_deterministic_and_black_is_valid(self):
        colors = {"STONE": (1, 2, 3), "CUSTOM_BLACK": (0, 0, 0)}
        first = self.root / "first.yml"
        second = self.root / "second.yml"
        TOOLS["write_colors"](first, colors)
        TOOLS["write_colors"](second, dict(reversed(list(colors.items()))))
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertIn("CUSTOM_BLACK:", first.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
