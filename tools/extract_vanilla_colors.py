"""从本地客户端资源生成原版方块代表色；不运行客户端、不联网、不修改客户端目录。"""
from __future__ import annotations

import argparse
from collections import Counter
from functools import lru_cache
import hashlib
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import re
import stat
from zipfile import ZipFile

from PIL import Image

EXCLUDED = {"AIR", "CAVE_AIR", "VOID_AIR", "LIGHT", "MOVING_PISTON"}
PREFIXES = ("assets/minecraft/blockstates/", "assets/minecraft/models/",
            "assets/minecraft/textures/", "assets/minecraft/items/",
            "data/minecraft/worldgen/biome/")
RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")


def digest(path: Path, algorithm="sha256") -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def resource_path(resource: str, kind: str, extension=".json") -> str:
    resource = resource if ":" in resource else "minecraft:" + resource
    if not RESOURCE_ID.fullmatch(resource):
        raise ValueError("非法资源位置：" + resource)
    namespace, name = resource.split(":", 1)
    if any(part in {"", ".", ".."} for part in name.split("/")):
        raise ValueError("资源路径包含目录穿越：" + resource)
    return f"assets/{namespace}/{kind}/{name}{extension}"


def texture_reference(value):
    if isinstance(value, str):
        return value
    # 26.3 的红石等模型把纹理变量写成带 sprite/force_translucent 的对象。
    if isinstance(value, dict) and isinstance(value.get("sprite"), str):
        return value["sprite"]
    raise ValueError("不支持的纹理引用：" + repr(value))


def extract_resources(jar: Path, destination: Path) -> int:
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("解包目标必须是新的空目录：" + str(destination))
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    total_size = count = 0
    with ZipFile(jar) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith(PREFIXES):
                continue
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in entry.filename:
                raise ValueError("拒绝不安全的归档路径：" + entry.filename)
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("拒绝归档中的符号链接：" + entry.filename)
            total_size += entry.file_size
            if entry.file_size > 64 * 1024 * 1024 or total_size > 1024 * 1024 * 1024:
                raise ValueError("资源解包大小超出上限")
            target = root.joinpath(*name.parts)
            if not target.resolve().is_relative_to(root):
                raise ValueError("归档条目超出目标目录")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
            count += 1
    return count


def client_jar(version_dir: Path) -> Path:
    """仅沿本地版本 JSON 的显式继承关系解析，不猜测或下载其他版本。"""
    visited = set()
    current = version_dir
    while current not in visited:
        visited.add(current)
        config_file = current / (current.name + ".json")
        config = json.loads(config_file.read_text(encoding="utf-8")) if config_file.is_file() else {}
        jar_id = config.get("jar", current.name)
        candidate = current / (jar_id + ".jar")
        if not candidate.is_file():
            candidate = current.parent / jar_id / (jar_id + ".jar")
        if candidate.is_file():
            with ZipFile(candidate) as archive:
                if any(name.startswith("assets/minecraft/blockstates/") for name in archive.namelist()):
                    return candidate
        parent = config.get("inheritsFrom")
        if not isinstance(parent, str) or "/" in parent or "\\" in parent or parent in {".", ".."}:
            break
        current = version_dir.parent / parent
    raise FileNotFoundError("未在指定版本及其本地父版本中找到原版方块资源")


def block_types(api_sources: Path) -> dict[str, str]:
    with ZipFile(api_sources) as archive:
        text = archive.read("org/bukkit/block/BlockType.java").decode("utf-8")
    values = dict(re.findall(r'\b([A-Z][A-Z_0-9]+)\s*=\s*getBlockType\("([a-z0-9_]+)"\)', text))
    if not values:
        raise ValueError("无法提取 Paper BlockType 清单")
    return {key: value for key, value in values.items() if key not in EXCLUDED}


def rgb_multiply(rgb, tint):
    return tuple(rgb[c] * tint[c] / 255 for c in range(3))


def combine(samples):
    total = sum(weight for rgb, weight in samples if rgb is not None and weight > 0)
    if total <= 0:
        return None
    return tuple(sum(rgb[c] * weight for rgb, weight in samples if rgb is not None and weight > 0) / total
                 for c in range(3))


def animation_frames(image: Image.Image, metadata):
    animation = metadata.get("animation") if isinstance(metadata, dict) else None
    if not isinstance(animation, dict):
        return [(image, 1)]
    width, height = image.size
    size = min(width, height)
    fw = animation.get("width", width if "height" in animation else size)
    fh = animation.get("height", height if "width" in animation else size)
    if not isinstance(fw, int) or not isinstance(fh, int) or fw <= 0 or fh <= 0 or width % fw or height % fh:
        raise ValueError("动画帧尺寸非法")
    count = width // fw * (height // fh)
    frames = animation.get("frames", list(range(count)))
    frametime = animation.get("frametime", 1)
    result = []
    for entry in frames:
        index = entry.get("index") if isinstance(entry, dict) else entry
        time = entry.get("time", frametime) if isinstance(entry, dict) else frametime
        if not isinstance(index, int) or not 0 <= index < count or not isinstance(time, int) or time <= 0:
            raise ValueError("动画帧索引或持续时间非法")
        x, y = index % (width // fw) * fw, index // (width // fw) * fh
        result.append((image.crop((x, y, x + fw, y + fh)), time))
    if not result:
        raise ValueError("动画帧列表为空")
    return result


def pixel_average(frames, uv=(0, 0, 16, 16)):
    red = green = blue = alpha = population = 0
    for image, duration in frames:
        width, height = image.size
        x0, x1 = sorted((round(uv[0] / 16 * width), round(uv[2] / 16 * width)))
        y0, y1 = sorted((round(uv[1] / 16 * height), round(uv[3] / 16 * height)))
        if x0 == x1 or y0 == y1:
            continue
        if (x1 - x0) * (y1 - y0) > 4 * width * height:
            raise ValueError("UV 区域重复次数过大")
        pixels = image.load()
        for y in range(y0, y1):
            for x in range(x0, x1):
                r, g, b, a = pixels[x % width, y % height]
                red += r * a * duration
                green += g * a * duration
                blue += b * a * duration
                alpha += a * duration
                population += 255 * duration
    return (None, 0) if alpha == 0 else ((red / alpha, green / alpha, blue / alpha), alpha / population)


class Extractor:
    def __init__(self, root: Path):
        self.root = root
        self.used_models = set()
        self.used_textures = set()
        self.notes = set()

    def read_json(self, relative):
        path = self.root / relative
        return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None

    @lru_cache(maxsize=None)
    def resolved_model(self, resource, stack=()):
        if resource in stack or len(stack) > 64:
            raise ValueError("模型 parent 循环/过深：" + resource)
        model = self.read_json(resource_path(resource, "models"))
        if model is None:
            if resource.startswith(("builtin/", "minecraft:builtin/")):
                return {"textures": {}, "elements": []}
            raise ValueError("模型不存在：" + resource)
        parent = self.resolved_model(model["parent"], stack + (resource,)) if "parent" in model else {}
        textures = dict(parent.get("textures", {}))
        textures.update(model.get("textures", {}))
        return {"textures": textures, "elements": model.get("elements", parent.get("elements", []))}

    @lru_cache(maxsize=None)
    def texture(self, resource, uv=(0, 0, 16, 16)):
        path = resource_path(resource, "textures", ".png")
        file = self.root / path
        if not file.is_file():
            raise ValueError("纹理不存在：" + resource)
        with Image.open(file) as original:
            image = original.convert("RGBA")
        return pixel_average(animation_frames(image, self.read_json(path + ".mcmeta")), uv)

    def resolve_texture(self, value, textures):
        seen = set()
        value = texture_reference(value)
        while value.startswith("#") or value in textures:
            if value in seen:
                raise ValueError("贴图变量循环：" + value)
            seen.add(value)
            key = value[1:] if value.startswith("#") else value
            if key not in textures:
                raise ValueError("缺少贴图变量：" + value)
            value = texture_reference(textures[key])
        return value

    @staticmethod
    def item_id(block):
        if block.startswith("potted_"):
            return block[7:]
        return block.replace("_wall_hanging_sign", "_hanging_sign").replace("_wall_sign", "_sign") \
            .replace("_wall_banner", "_banner").replace("_wall_head", "_head").replace("_wall_skull", "_skull")

    def item_nodes(self, node):
        if not isinstance(node, dict):
            return []
        kind = node.get("type", "").removeprefix("minecraft:")
        if kind == "select" and isinstance(node.get("fallback"), dict):
            # 代表色不随客户端日期改变，箱子取正常日的 fallback，而非圣诞皮肤。
            return self.item_nodes(node["fallback"])
        if kind == "composite":
            return [leaf for child in node.get("models", []) for leaf in self.item_nodes(child)]
        if kind in {"model", "special"}:
            return [node]
        return []

    def water_tint(self):
        biome = self.read_json("data/minecraft/worldgen/biome/plains.json") or {}
        value = biome.get("effects", {}).get("water_color")
        if isinstance(value, str) and value.startswith("#"):
            value = int(value[1:], 16)
        if not isinstance(value, int):
            raise ValueError("客户端没有可追溯的代表生物群系水色")
        self.notes.add("water tint: actual plains biome water_color; biome representative")
        return ((value >> 16) & 255, (value >> 8) & 255, value & 255)

    def tint(self, block):
        if block == "water_cauldron":
            return [self.water_tint()]
        item = self.read_json(resource_path(self.item_id(block), "items")) or {}
        nodes = self.item_nodes(item.get("model"))
        tints = next((node["tints"] for node in nodes if node.get("tints")), [])
        result = []
        for entry in tints:
            kind = entry.get("type", "").removeprefix("minecraft:")
            if kind == "constant":
                value = entry["value"] & 0xFFFFFF
                result.append(((value >> 16) & 255, (value >> 8) & 255, value & 255))
            elif kind == "grass":
                temp = max(0, min(1, entry.get("temperature", .5)))
                humidity = max(0, min(1, entry.get("downfall", 1))) * temp
                path = resource_path("minecraft:colormap/grass", "textures", ".png")
                with Image.open(self.root / path) as image:
                    result.append(image.convert("RGB").getpixel((int((1 - temp) * 255), int((1 - humidity) * 255))))
                self.notes.add("grass_tint: item temperature/downfall + client colormap; biome representative")
            else:
                raise ValueError("无法从资源可靠解析染色器：" + kind)
        return result

    def special(self, block):
        item = self.read_json(resource_path(self.item_id(block), "items")) or {}
        for node in self.item_nodes(item.get("model")):
            if node.get("type") != "minecraft:special":
                continue
            model = node.get("model", {})
            kind = model.get("type", "").removeprefix("minecraft:")
            reference = model.get("texture")
            if kind in {"chest", "shulker_box"} and isinstance(reference, str):
                namespace, name = reference.split(":", 1) if ":" in reference else ("minecraft", reference)
                folder = "chest" if kind == "chest" else "shulker"
                texture = namespace + ":entity/" + folder + "/" + name
                color, opacity = self.texture(texture)
                self.used_textures.add(texture)
                self.notes.add("special renderer: actual entity atlas alpha-weighted representative")
                return color
            if kind == "player_head":
                raise ValueError("玩家头颅由动态皮肤决定，不生成伪造默认纹理颜色")
            if kind == "banner":
                raise ValueError("旗帜颜色和图案为动态合成，保留地图色回退")
            if kind == "head":
                head = model.get("kind")
                candidates = {
                    "skeleton": "entity/skeleton/skeleton", "wither_skeleton": "entity/skeleton/wither_skeleton",
                    "zombie": "entity/zombie/zombie", "creeper": "entity/creeper/creeper",
                    "piglin": "entity/piglin/piglin", "dragon": "entity/enderdragon/dragon"}
                texture = candidates.get(head)
                if texture:
                    # 头颅仅取头部正面；龙模型不具有常规皮肤 UV，明确使用整张 atlas 近似。
                    texture = "minecraft:" + texture
                    image_path = self.root / resource_path(texture, "textures", ".png")
                    with Image.open(image_path) as image:
                        uv = (8 / image.width * 16, 8 / image.height * 16,
                              16 / image.width * 16, 16 / image.height * 16) if head != "dragon" else (0, 0, 16, 16)
                    color, opacity = self.texture(texture, uv)
                    self.used_textures.add(texture)
                    self.notes.add("special head: fixed vanilla texture, not a player skin")
                    return color
        return None

    def model_color(self, resource, tints):
        self.used_models.add(resource)
        model = self.resolved_model(resource)
        textures = model["textures"]
        samples = []
        for element in model["elements"]:
            start, end = element["from"], element["to"]
            dx, dy, dz = (abs(end[i] - start[i]) for i in range(3))
            for side, face in element.get("faces", {}).items():
                area = dx * dz if side in {"up", "down"} else dy * (dx if side in {"north", "south"} else dz)
                if area <= 0:
                    continue
                texture = self.resolve_texture(face["texture"], textures)
                uv = face.get("uv")
                if uv is None:
                    uv = [start[0], start[2], end[0], end[2]] if side in {"up", "down"} else \
                        [start[0] if side in {"north", "south"} else start[2], 16 - end[1],
                         end[0] if side in {"north", "south"} else end[2], 16 - start[1]]
                color, opacity = self.texture(texture, tuple(uv))
                self.used_textures.add(texture)
                tintindex = face.get("tintindex", -1)
                if color is not None and 0 <= tintindex < len(tints):
                    color = rgb_multiply(color, tints[tintindex])
                    self.notes.add("tint applied from actual client item definition")
                samples.append((color, area * opacity))
        if not samples and textures.get("particle"):
            texture = self.resolve_texture(textures["particle"], textures)
            color, opacity = self.texture(texture)
            self.used_textures.add(texture)
            self.notes.add("particle representative: no ordinary block mesh")
            samples.append((color, opacity))
        return combine(samples)

    def block_color(self, block, state):
        # 以下染色由世界状态/代码计算，不能把灰度纹理当成最终颜色。
        if block in {"water", "bubble_column"}:
            texture = "minecraft:block/water_still"
            rgb, opacity = self.texture(texture)
            self.used_textures.add(texture)
            self.notes.add("water texture representative; no refraction/transparency compositing")
            return None if rgb is None else rgb_multiply(rgb, self.water_tint())
        if block in {"redstone_wire", "melon_stem",
                     "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem"}:
            raise ValueError("世界状态/环境动态染色：保留实际 BlockData 地图色，不写灰度替代值")
        special = self.special(block)
        if special is not None:
            return special
        tints = self.tint(block)
        samples = []
        for rule in state.get("variants", {}).values():
            alternatives = rule if isinstance(rule, list) else [rule]
            total = sum(max(1, alternative.get("weight", 1)) for alternative in alternatives)
            for alternative in alternatives:
                samples.append((self.model_color(alternative["model"], tints), max(1, alternative.get("weight", 1)) / total))
        for part in state.get("multipart", []):
            alternatives = part["apply"] if isinstance(part["apply"], list) else [part["apply"]]
            total = sum(max(1, alternative.get("weight", 1)) for alternative in alternatives)
            # 每个有条件组件使用相同代表概率；不是按任意状态强行全选为精确几何。
            probability = .5 if "when" in part else 1
            for alternative in alternatives:
                samples.append((self.model_color(alternative["model"], tints), probability * max(1, alternative.get("weight", 1)) / total))
        if state.get("multipart"):
            self.notes.add("multipart/state averaged representative; not per-state exact shading")
        return combine(samples)

    def generate(self, targets):
        colors, details, unresolved = {}, {}, {}
        for material, block in sorted(targets.items()):
            self.used_models = set()
            self.used_textures = set()
            self.notes = set()
            try:
                state = self.read_json(resource_path(block, "blockstates"))
                if state is None:
                    raise ValueError("客户端缺少方块状态定义")
                color = self.block_color(block, state)
                if color is None:
                    raise ValueError("纹理全透明或没有可用的模型/纹理")
                rgb = tuple(max(0, min(255, int(channel))) for channel in color)
                colors[material] = rgb
                details[material] = {"rgb": rgb, "block": "minecraft:" + block,
                                     "models": sorted(self.used_models), "textures": sorted(self.used_textures),
                                     "notes": sorted(self.notes)}
            except (ValueError, KeyError, OSError, TypeError) as error:
                unresolved[material] = str(error)
        return colors, details, unresolved


def write_colors(path: Path, colors):
    lines = ["version: 8", "# 从本地 Minecraft 26.3 客户端资源生成，来源及覆盖见 vanilla-colors-report.json。",
             "# 单个 RGB 为贴图代表色；动态环境/特殊模型未可靠解析时仍用实际 BlockData 地图色。",
             "# 手动覆盖写入 color-overrides.yml，合法纯黑同样有效。"]
    for material, (red, green, blue) in sorted(colors.items()):
        lines.extend([material + ":", f"  r: {red}", f"  g: {green}", f"  b: {blue}"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, required=True, help="只读的客户端版本目录")
    parser.add_argument("--api-sources", type=Path, required=True, help="Paper API sources JAR")
    source_group = parser.add_mutually_exclusive_group(required=True)
    source_group.add_argument("--extract-dir", type=Path, help="必须为新的空目录")
    source_group.add_argument("--resources", type=Path, help="重用本工具已提取的只读资源目录")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    jar = client_jar(args.client.resolve())
    if args.extract_dir is not None:
        if args.extract_dir.resolve().is_relative_to(args.client.resolve()):
            raise ValueError("禁止写入客户端安装目录")
        count = extract_resources(jar, args.extract_dir)
        resources = args.extract_dir
    else:
        resources = args.resources
        count = sum(p.is_file() for p in resources.rglob("*"))
    with ZipFile(jar) as archive:
        version = json.loads(archive.read("version.json"))
        client_states = {PurePosixPath(name).stem for name in archive.namelist()
                         if name.startswith("assets/minecraft/blockstates/") and name.endswith(".json")}
    targets = block_types(args.api_sources)
    extractor = Extractor(resources)
    colors, details, unresolved = extractor.generate(targets)
    report = {"schema": 1, "algorithm": "client-face-uv-area-alpha-time-weighted-srgb-v1",
              "source": {"version": version.get("id"), "client_jar": jar.name,
                         "client_sha1": digest(jar, "sha1"), "client_sha256": digest(jar),
                         "api_sources": args.api_sources.name, "api_sources_sha256": digest(args.api_sources)},
              "coverage": {"client_blockstates": len(client_states), "server_renderable_blocks": len(targets),
                           "generated_colors": len(colors), "map_color_fallbacks": len(unresolved),
                           "extracted_resource_files": count,
                           "client_only_states": sorted(client_states - set(targets.values()) - {n.lower() for n in EXCLUDED})},
              "colors": details, "map_color_fallbacks": unresolved}
    write_colors(args.output, colors)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["coverage"], ensure_ascii=True))
    print("CHERRY_LEAVES:", colors.get("CHERRY_LEAVES"))
    print("MAP_COLOR_FALLBACKS:", json.dumps(unresolved, ensure_ascii=True))


if __name__ == "__main__":
    main()
