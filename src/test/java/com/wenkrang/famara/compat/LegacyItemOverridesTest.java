package com.wenkrang.famara.compat;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 供用户运行：旧整数 CMD 与旧式 overrides 的回归用例。 */
class LegacyItemOverridesTest {
    private final Map<String, byte[]> files = new HashMap<>();
    private void json(String path, String text) { files.put(path, text.getBytes(StandardCharsets.UTF_8)); }
    private void color(String name, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff000000 | rgb);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        files.put("assets/test/textures/" + name + ".png", out.toByteArray());
        json("assets/test/models/item/" + name + ".json", "{\"textures\":{\"layer0\":\"test:" + name + "\"}}");
    }
    private PackColorIndex.Result build() throws Exception {
        return PackColorIndex.build(new PackColorIndex.PackSource() {
            public Collection<String> list() { return files.keySet(); }
            public byte[] read(String path) { return files.get(path); }
        }, name -> null);
    }
    private PackColorIndex.ItemState cmd(int value) {
        return new PackColorIndex.ItemState(List.of(), List.of(), List.of((float) value), "fixed", 0, 1, value);
    }

    @Test void thresholdAndLastMatchingOverrideChooseRealCustomModel() throws Exception {
        color("red", 0xff0000); color("blue", 0x0000ff);
        json("assets/minecraft/models/item/stick.json", """
                {"overrides":[
                  {"predicate":{"custom_model_data":10},"model":"test:item/red"},
                  {"predicate":{"custom_model_data":20},"model":"test:item/blue"}]}
                """);
        var result = build();
        assertEquals(0xff0000, result.itemColor("minecraft:stick", cmd(10)));
        assertEquals(0x0000ff, result.itemColor("minecraft:stick", cmd(20)));
        assertEquals(0x0000ff, result.itemColor("minecraft:stick", cmd(30)));
    }

    @Test void unmatchedCustomCmdCannotMasqueradeAsVanillaCarrierColor() throws Exception {
        color("red", 0xff0000);
        json("assets/minecraft/models/item/paper.json", """
                {"textures":{"layer0":"test:red"},"overrides":[
                {"predicate":{"custom_model_data":100},"model":"test:item/red"}]}
                """);
        var result = build();
        assertEquals(0xff0000, result.itemColor("minecraft:paper", cmd(0)));
        assertNull(result.itemColor("minecraft:paper", cmd(1)));
    }

    @Test void legacyOverrideLoopIsDiagnosed() throws Exception {
        json("assets/test/models/item/a.json", """
                {"overrides":[{"predicate":{"custom_model_data":1},"model":"test:item/b"}]}
                """);
        json("assets/test/models/item/b.json", """
                {"overrides":[{"predicate":{"custom_model_data":1},"model":"test:item/a"}]}
                """);
        var result = build();
        assertNull(result.itemColor("test:a", cmd(1)));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.contains("overrides")));
    }

    @Test void legacyCmdUsesLegacyOverridesEvenWhenModernDefinitionCoexists() throws Exception {
        color("red", 0xff0000); color("blue", 0x0000ff);
        json("assets/minecraft/items/stick.json", """
                {"model":{"type":"minecraft:model","model":"test:item/blue"}}
                """);
        json("assets/minecraft/models/item/stick.json", """
                {"overrides":[{"predicate":{"custom_model_data":10},"model":"test:item/red"}]}
                """);
        var result = build();
        assertEquals(0xff0000, result.itemColor("minecraft:stick", cmd(10)));
        assertEquals(0x0000ff, result.itemColor("minecraft:stick", PackColorIndex.ItemState.EMPTY));
    }

    @Test void customCmdWithoutCarrierOverrideDoesNotUseVanillaBase() throws Exception {
        color("red", 0xff0000);
        json("assets/minecraft/models/item/stick.json", """
                {"textures":{"layer0":"test:red"}}
                """);
        var result = build();
        assertNull(result.itemColor("minecraft:stick", cmd(10)));
        assertEquals(0xff0000, result.itemColor("minecraft:stick", cmd(0)));
    }

}
