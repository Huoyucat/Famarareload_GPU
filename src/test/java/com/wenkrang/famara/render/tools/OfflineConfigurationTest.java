package com.wenkrang.famara.render.tools;

import com.google.gson.*;
import com.wenkrang.famara.render.asset.ResourceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class OfflineConfigurationTest {
    @TempDir
    Path directory;

    private JsonObject example() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("docs/render-v2/offline-experiment.json"))).getAsJsonObject();
    }

    private Path write(JsonObject json) throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, json.toString());
        return path;
    }

    @Test
    void explicitExampleHasNoImplicitProductionDefaults() throws Exception {
        var config = OfflinePipelineDemo.configuration(write(example()));
        assertEquals(200, config.pipeline().cpuMillisPerSecond());
        assertEquals(com.wenkrang.famara.render.kernel.Camera.Projection.LEGACY_ANGULAR, config.shot().camera().projection());
        assertEquals(18, config.scene().sections().size());
        assertTrue(config.cePacks().isEmpty());
    }

    @Test
    void absentEvenOtherwiseValidPrimitiveCannotSilentlyDefault() throws Exception {
        var json = example();
        json.getAsJsonObject("shot").getAsJsonObject("settings").remove("backgroundRgb");
        Path file = write(json);
        assertThrows(IllegalArgumentException.class, () -> OfflinePipelineDemo.configuration(file));
    }

    @Test
    void unknownConfigurationIsRejectedInsteadOfIgnored() throws Exception {
        var json = example();
        json.addProperty("workers_typo", 3);
        Path file = write(json);
        assertThrows(IllegalArgumentException.class, () -> OfflinePipelineDemo.configuration(file));
    }

    @Test
    void materialOverridesUseValidatedResourceKeys() throws Exception {
        var json = example();
        json.getAsJsonObject("shot").getAsJsonObject("settings").getAsJsonObject("materialOverrides").addProperty("test:tile", "TRANSLUCENT");
        var config = OfflinePipelineDemo.configuration(write(json));
        assertTrue(config.shot().settings().materialOverrides().containsKey(ResourceKey.parse("test:tile")));
    }
}
