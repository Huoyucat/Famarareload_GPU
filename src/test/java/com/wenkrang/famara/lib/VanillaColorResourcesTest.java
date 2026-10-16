package com.wenkrang.famara.lib;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class VanillaColorResourcesTest {
    @Test void everyPublishedRgbHasTraceableClientResourceProvenance() throws Exception {
        JsonObject report;
        try (var input = getClass().getResourceAsStream("/vanilla-colors-report.json")) {
            assertNotNull(input);
            report = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var yaml = ColorManager.yaml();
        try (var input = getClass().getResourceAsStream("/colors.yml")) {
            assertNotNull(input);
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        var parsed = ColorManager.parse(yaml, new ArrayList<>());
        var records = report.getAsJsonObject("colors");
        assertEquals(1242, parsed.size());
        assertEquals(parsed.size(), records.size());
        assertEquals("26.3", report.getAsJsonObject("source").get("version").getAsString());
        assertEquals("e877b6a07acd633fb3bb475002175cec036e7b87", report.getAsJsonObject("source").get("client_sha1").getAsString());
        var coverage = report.getAsJsonObject("coverage");
        assertEquals(1281, coverage.get("server_renderable_blocks").getAsInt());
        assertEquals(1281, coverage.get("generated_colors").getAsInt() + coverage.get("map_color_fallbacks").getAsInt());
        for (var entry : parsed.entrySet()) {
            var record = records.getAsJsonObject(entry.getKey());
            assertNotNull(record, entry.getKey());
            assertFalse(record.getAsJsonArray("textures").isEmpty(), entry.getKey());
            var rgb = record.getAsJsonArray("rgb");
            assertEquals(entry.getValue(), rgb.get(0).getAsInt() << 16 | rgb.get(1).getAsInt() << 8 | rgb.get(2).getAsInt());
        }
        for (var fallback : report.getAsJsonObject("map_color_fallbacks").entrySet())
            assertFalse(parsed.containsKey(fallback.getKey()), fallback.getKey());
    }

    @Test void newAndPreviouslyMissingBlockFamiliesAllHaveExtractedColors() throws Exception {
        var values = ColorManager.yaml();
        try (var input = getClass().getResourceAsStream("/colors.yml")) {
            assertNotNull(input);
            values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        var colors = ColorManager.parse(values, new ArrayList<>());
        for (String name : java.util.List.of("CHERRY_LEAVES", "POPLAR_LOG", "CINNABAR", "SULFUR", "RED_CONCRETE_STAIRS",
                "BLUE_WOOL_SLAB", "COPPER_CHEST", "OXIDIZED_COPPER_CHEST", "OAK_WALL_HANGING_SIGN", "POTTED_FERN",
                "LAVA", "WATER", "HEAVY_CORE")) assertNotNull(colors.get(name), name);
        assertEquals(0xe5acc2, colors.get("CHERRY_LEAVES"));
    }
}
