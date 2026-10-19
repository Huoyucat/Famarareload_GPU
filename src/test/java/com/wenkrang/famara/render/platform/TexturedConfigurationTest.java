package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TexturedConfigurationTest {
    @TempDir Path directory;
    private JsonObject example()throws Exception{return JsonParser.parseString(Files.readString(Path.of("docs/render-v2/runtime-experiment.json"))).getAsJsonObject();}
    private Path write(JsonObject json)throws Exception {Path file=directory.resolve("config.json");Files.writeString(file,json.toString());return file;}
    @Test void exampleRequiresExplicitBudgetsAndHasNoMachineAbsolutePath()throws Exception {
        var configuration=TexturedConfiguration.read(write(example()));
        assertFalse(Path.of(configuration.baseResources()).isAbsolute());assertEquals(16,configuration.shot().distance());
        assertTrue(TexturedConfiguration.minimumSceneBytes(configuration.preparation())<=configuration.pipeline().sceneMemoryBytes());
        assertEquals(128,configuration.maps().maximumCachedFrames());
    }
    @Test void missingOrUnknownNestedBudgetNeverDefaultsSilently()throws Exception {
        var json=example();json.getAsJsonObject("capture").remove("maximumSnapshotBytes");assertThrows(IllegalArgumentException.class,()->TexturedConfiguration.read(write(json)));
        var typo=example();typo.getAsJsonObject("maps").addProperty("maximumCacheTypo",1);assertThrows(IllegalArgumentException.class,()->TexturedConfiguration.read(write(typo)));
    }
    @Test void fractionalOrQuotedIntegerIsRejectedInsteadOfTruncated()throws Exception {
        var fractional=example();fractional.getAsJsonObject("pipeline").addProperty("workers",1.5);assertThrows(IllegalArgumentException.class,()->TexturedConfiguration.read(write(fractional)));
        var quoted=example();quoted.getAsJsonObject("capture").addProperty("maxInflight","1");assertThrows(IllegalArgumentException.class,()->TexturedConfiguration.read(write(quoted)));
    }
    @Test void insufficientSceneBudgetOrExcessCpuReservationCannotStart()throws Exception {
        var json=example();json.getAsJsonObject("pipeline").addProperty("sceneMemoryBytes",1024);assertThrows(RuntimeException.class,()->TexturedConfiguration.read(write(json)));
        var cpu=example();cpu.getAsJsonObject("io").addProperty("quantumNanos",1000000000);assertThrows(RuntimeException.class,()->TexturedConfiguration.read(write(cpu)));
    }
}
