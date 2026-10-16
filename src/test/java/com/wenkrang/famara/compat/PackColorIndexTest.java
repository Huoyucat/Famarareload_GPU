package com.wenkrang.famara.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PackColorIndexTest {
    private final Map<String, byte[]> files = new HashMap<>();
    private void json(String path, String value) { files.put(path, value.getBytes(StandardCharsets.UTF_8)); }
    private void item(String name, String node) { json("assets/test/items/" + name + ".json", "{\"model\":" + node + "}"); }
    private String model(String name) { return "{\"type\":\"minecraft:model\",\"model\":\"test:" + name + "\"}"; }
    private void png(String name, int color) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xff000000 | color);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        files.put("assets/test/textures/" + name + ".png", out.toByteArray());
    }
    private void solid(String name, int color) throws Exception {
        png(name, color);
        json("assets/test/models/" + name + ".json", "{\"textures\":{\"all\":\"test:" + name + "\"}}");
    }
    private PackColorIndex.Result build() throws Exception {
        return PackColorIndex.build(new PackColorIndex.PackSource() {
            public Collection<String> list() { return files.keySet(); }
            public byte[] read(String path) { return files.get(path); }
        }, name -> name.equals("STONE") ? 0x888888 : null);
    }
    private Integer itemColor(PackColorIndex.Result result, String id) {
        return result.itemColor("test:" + id, PackColorIndex.ItemState.EMPTY);
    }

    @Test void partialVariantMatchesFullStateAndSpecificRuleWins() throws Exception {
        solid("red", 0xff0000); solid("blue", 0x0000ff);
        json("assets/test/blockstates/block.json", """
                {"variants":{"":{"model":"test:blue"},"facing=north":{"model":"test:red"}}}
                """);
        var result = build();
        assertEquals(0xff0000, result.stateColor("test:block[waterlogged=false,facing=north]"));
        assertEquals(0x0000ff, result.stateColor("Block{test:block}[facing=south,waterlogged=false]"));
    }

    @Test void multipartUsesAndOrAndValueAlternatives() throws Exception {
        solid("red", 0xff0000); solid("blue", 0x0000ff);
        json("assets/test/blockstates/fence.json", """
                {"multipart":[
                  {"apply":{"model":"test:red"}},
                  {"when":{"OR":[{"north":"true|low"},{"AND":[{"south":"true"},{"waterlogged":"false"}]}]},
                   "apply":{"model":"test:blue"}}
                ]}
                """);
        var result = build();
        assertEquals(0x7f007f, result.stateColor("test:fence[north=low,south=false,waterlogged=true]"));
        assertEquals(0x7f007f, result.stateColor("test:fence[north=false,south=true,waterlogged=false]"));
        assertEquals(0xff0000, result.stateColor("test:fence[north=false,south=true,waterlogged=true]"));
    }

    @Test void weightedVariantsDoNotAlwaysSelectFirst() throws Exception {
        solid("red", 0xff0000); solid("blue", 0x0000ff);
        json("assets/test/blockstates/weighted.json", """
                {"variants":{"": [{"model":"test:red","weight":1},{"model":"test:blue","weight":3}]}}
                """);
        assertEquals(0x3f00bf, build().stateColor("test:weighted"));
    }

    @Test void parentTexturesAndVariablesAreInherited() throws Exception {
        png("red", 0xff0000);
        json("assets/test/models/parent.json", "{\"textures\":{\"all\":\"#base\"}}");
        json("assets/test/models/child.json", "{\"parent\":\"test:parent\",\"textures\":{\"base\":\"test:red\"}}");
        item("chair", model("child"));
        assertEquals(0xff0000, itemColor(build(), "chair"));
    }

    @Test void newerSpriteObjectTextureReferencesAreSupported() throws Exception {
        png("red", 0xff0000);
        json("assets/test/models/sprite.json", "{\"textures\":{\"all\":{\"sprite\":\"test:red\",\"force_translucent\":true}}}");
        item("sprite", model("sprite"));
        assertEquals(0xff0000, itemColor(build(), "sprite"));
    }

    @Test void actualCameraConfigIsEmptyOnlyForOpenedFirstPersonContexts() throws Exception {
        solid("open", 0x22ff22); solid("closed", 0x2222ff);
        var raw = new org.yaml.snakeyaml.Yaml().loadAs(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/craftengine/famara/configuration/items.yml")), java.util.Map.class);
        var camera = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) raw.get("items")).get("famara:camera");
        var node = new com.google.gson.Gson().toJsonTree(camera.get("model")).getAsJsonObject();
        convertCeModelPaths(node);
        item("camera", node.toString());
        var result = build();
        for (String context : java.util.List.of("firstperson_righthand", "firstperson_lefthand")) {
            var opened = new PackColorIndex.ItemState(java.util.List.of(true), java.util.List.of(), java.util.List.of(), context, 0, 1);
            var closed = new PackColorIndex.ItemState(java.util.List.of(false), java.util.List.of(), java.util.List.of(), context, 0, 1);
            assertNull(result.itemColor("test:camera", opened), context);
            assertEquals(0x2222ff, result.itemColor("test:camera", closed), context);
        }
        for (String context : java.util.List.of("gui", "ground", "fixed", "thirdperson_righthand", "thirdperson_lefthand")) {
            var opened = new PackColorIndex.ItemState(java.util.List.of(true), java.util.List.of(), java.util.List.of(), context, 0, 1);
            assertEquals(0x22ff22, result.itemColor("test:camera", opened), context);
        }
    }

    private void convertCeModelPaths(com.google.gson.JsonElement node) {
        if (node.isJsonObject()) {
            var object = node.getAsJsonObject();
            if (object.has("path")) {
                String reference = object.remove("path").getAsString();
                assertTrue(java.util.Set.of("famara:item/famara_open", "famara:item/famara_close").contains(reference));
                object.addProperty("model", reference.endsWith("_open") ? "test:open" : "test:closed");
            }
            for (var entry : object.entrySet()) convertCeModelPaths(entry.getValue());
        } else if (node.isJsonArray()) for (var child : node.getAsJsonArray()) convertCeModelPaths(child);
    }

    @Test void cameraConditionUsesActualCustomModelDataFlag() throws Exception {
        solid("open", 0x22ff22); solid("closed", 0x2222ff);
        item("camera", "{\"type\":\"minecraft:condition\",\"property\":\"minecraft:custom_model_data\",\"index\":0,"
                + "\"on_true\":" + model("open") + ",\"on_false\":" + model("closed") + "}");
        var result = build();
        assertEquals(0x2222ff, itemColor(result, "camera"));
        var opened = new PackColorIndex.ItemState(List.of(true), List.of(), List.of(), "ground", 0, 1);
        assertEquals(0x22ff22, result.itemColor("test:camera", opened));
    }

    @Test void selectUsesDisplayContextAndCustomStrings() throws Exception {
        solid("red", 0xff0000); solid("blue", 0x0000ff);
        item("context", "{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                + "\"cases\":[{\"when\":[\"ground\",\"fixed\"],\"model\":" + model("red") + "}],\"fallback\":" + model("blue") + "}");
        assertEquals(0xff0000, itemColor(build(), "context"));
        item("context", "{\"type\":\"minecraft:select\",\"property\":\"minecraft:custom_model_data\","
                + "\"cases\":[{\"when\":\"special\",\"model\":" + model("red") + "}],\"fallback\":" + model("blue") + "}");
        var state = new PackColorIndex.ItemState(List.of(), List.of("special"), List.of(), "none", 0, 1);
        assertEquals(0xff0000, build().itemColor("test:context", state));
    }

    @Test void rangeDispatchAndCompositeAreDeterministic() throws Exception {
        solid("red", 0xff0000); solid("blue", 0x0000ff);
        item("range", "{\"type\":\"minecraft:range_dispatch\",\"property\":\"minecraft:custom_model_data\","
                + "\"entries\":[{\"threshold\":3,\"model\":" + model("red") + "}],\"fallback\":" + model("blue") + "}");
        var result = build();
        assertEquals(0x0000ff, itemColor(result, "range"));
        assertEquals(0xff0000, result.itemColor("test:range", new PackColorIndex.ItemState(List.of(), List.of(), List.of(4f), "fixed", 0, 1)));
        item("both", "{\"type\":\"minecraft:composite\",\"models\":[" + model("red") + "," + model("blue") + "]}");
        assertEquals(0x7f007f, itemColor(build(), "both"));
    }

    @Test void unsupportedNodeIsReportedNotArbitrarilySelected() throws Exception {
        solid("red", 0xff0000);
        item("unknown", "{\"type\":\"minecraft:condition\",\"property\":\"test:unknown\",\"on_true\":" + model("red") + "}");
        var result = build();
        assertNull(itemColor(result, "unknown"));
        assertFalse(result.issues().isEmpty());
    }

    @Test void malformedResourceDoesNotDiscardOtherValidFiles() throws Exception {
        solid("red", 0xff0000); item("valid", model("red"));
        json("assets/test/items/invalid.json", "{broken");
        var result = build();
        assertEquals(0xff0000, itemColor(result, "valid"));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("invalid.json")));
    }

    @Test void parentCycleAndMissingTexturesAreDiagnosed() throws Exception {
        json("assets/test/models/a.json", "{\"parent\":\"test:b\"}");
        json("assets/test/models/b.json", "{\"parent\":\"test:a\"}");
        item("cycle", model("a"));
        json("assets/test/models/missing.json", "{\"textures\":{\"all\":\"test:absent\"}}");
        item("missing", model("missing"));
        var result = build();
        assertNull(itemColor(result, "cycle"));
        assertNull(itemColor(result, "missing"));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("parent")));
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("absent")));
    }

    @Test void vanillaParentCanUseKnownMapColorFallback() throws Exception {
        json("assets/test/models/vanilla.json", "{\"parent\":\"minecraft:block/stone\"}");
        item("stone", model("vanilla"));
        // 包含普通 cube_all 的 CE 模型已有自己的 textures；纯原版模型直接引用也能回退。
        item("direct", "{\"type\":\"minecraft:model\",\"model\":\"minecraft:block/stone\"}");
        assertEquals(0x888888, itemColor(build(), "direct"));
        assertEquals(0x888888, itemColor(build(), "stone"));
    }

    @Test void staticNonSquareTextureUsesFullImage() {
        BufferedImage image = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000); image.setRGB(0, 1, 0xff0000ff);
        assertEquals(0x7f007f, PackColorIndex.imageColor(image, null));
    }

    @Test void animationUsesFirstDeclaredFrame() {
        BufferedImage image = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000); image.setRGB(0, 1, 0xff0000ff);
        var metadata = JsonParser.parseString("{\"animation\":{\"frames\":[1,0]}}").getAsJsonObject();
        assertEquals(0x0000ff, PackColorIndex.imageColor(image, metadata));
    }

    @Test void alphaWeightedPixelsAndTransparentImageAreHandled() {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000); image.setRGB(1, 0, 0x550000ff);
        assertEquals(0xbf003f, PackColorIndex.imageColor(image, null));
        image.setRGB(0, 0, 0); image.setRGB(1, 0, 0);
        assertNull(PackColorIndex.imageColor(image, null));
    }
}
