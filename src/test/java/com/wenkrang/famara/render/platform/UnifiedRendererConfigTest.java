package com.wenkrang.famara.render.platform;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedRendererConfigTest {
    @TempDir Path root;
    private String defaults()throws Exception{return Files.readString(Path.of("src/main/resources/config.yml"));}
    private Path config(String content)throws Exception {Path path=root.resolve("config.yml");Files.writeString(path,content);return path;}
    @Test void freshInstallIsTextured128WithBoundedLodDefaults()throws Exception {
        var loaded=UnifiedRendererConfig.load(root,defaults());var policy=loaded.renderer();
        assertEquals(PhotoRuntime.Mode.TEXTURED,loaded.mode());assertEquals(128,policy.shot().distance());
        assertEquals(402653184,policy.capture().maximumSnapshotBytes());assertEquals(384,policy.capture().maxChunksPerRequest());
        assertEquals(65536,policy.preparation().maximumInstances());assertEquals(201326592,TexturedConfiguration.minimumSceneBytes(policy.preparation()));
        assertEquals(1000,policy.loading().cpuMillisPerSecond());assertEquals(512,policy.visuals().entriesPerTick());
        assertFalse(loaded.yaml().contains("render-v2.shot.distance"));assertFalse(Files.exists(root.resolve("render-v2.json")));
    }
    @Test void migrationPreservesPathsModeDistanceAndUpgradesOnlyKnownTemplateDefaults()throws Exception {
        config("version: 5\nrenderer-mode: LEGACY\nrender-distance: 32\ntextured-config: previous.json\n");
        JsonObject old=JsonParser.parseString(Files.readString(Path.of("docs/render-v2/runtime-experiment.json"))).getAsJsonObject();old.remove("loading");
        old.addProperty("baseResources","D:/private/input.jar");old.getAsJsonObject("pipeline").addProperty("cpuMillisPerSecond",333);
        Files.writeString(root.resolve("previous.json"),old.toString());Path pictures=Files.createDirectories(root.resolve("pictures"));Files.writeString(pictures.resolve("7.png"),"preserve");
        Path ledger=Files.createDirectories(root.resolve("camera-ledger-v2"));Files.writeString(ledger.resolve("ledger.json"),"untouched");
        var loaded=UnifiedRendererConfig.load(root,defaults());
        assertEquals(PhotoRuntime.Mode.LEGACY,loaded.mode());assertEquals(32,loaded.renderer().shot().distance());assertEquals("D:/private/input.jar",loaded.renderer().baseResources());
        assertEquals(333,loaded.renderer().pipeline().cpuMillisPerSecond());assertEquals(65536,loaded.renderer().preparation().maximumInstances());
        assertEquals(512,loaded.renderer().visuals().entriesPerTick());assertEquals(1000000,loaded.renderer().assetBuild().dispatchNanos());
        assertEquals(402653184,loaded.renderer().capture().maximumSnapshotBytes());assertEquals(384,loaded.renderer().capture().maxChunksPerRequest());
        assertTrue(Files.exists(root.resolve("previous.json")));assertEquals("preserve",Files.readString(pictures.resolve("7.png")));assertEquals("untouched",Files.readString(ledger.resolve("ledger.json")));
        try(var files=Files.list(root)){assertEquals(1,files.filter(p->p.toString().endsWith(".bak")).count());}
    }
    @Test void yamlOverridesJsonAndCustomLimitsAreNotResetToDefaults()throws Exception {
        config("renderer-mode: TEXTURED\nrender-distance: 48\nrender-v2:\n  baseResources: local/base.jar\n  capture:\n    maximumSnapshotBytes: 402653184\n    maxChunksPerRequest: 192\n  preparation:\n    maximumInstances: 9000\n");
        JsonObject old=JsonParser.parseString(Files.readString(Path.of("docs/render-v2/runtime-experiment.json"))).getAsJsonObject();Files.writeString(root.resolve("render-v2.json"),old.toString());
        var loaded=UnifiedRendererConfig.load(root,defaults());assertEquals("local/base.jar",loaded.renderer().baseResources());
        assertEquals(402653184,loaded.renderer().capture().maximumSnapshotBytes());assertEquals(9000,loaded.renderer().preparation().maximumInstances());assertEquals(48,loaded.renderer().shot().distance());
    }
    @Test void secondMigrationIsByteStableAndDoesNotRereadRetainedJson()throws Exception {
        config("render-distance: 32\n");UnifiedRendererConfig.load(root,defaults());byte[] first=Files.readAllBytes(root.resolve("config.yml"));
        Files.writeString(root.resolve("render-v2.json"),"this is now a broken retained file");
        var second=UnifiedRendererConfig.load(root,defaults());assertTrue(second.changes().isEmpty());assertArrayEquals(first,Files.readAllBytes(root.resolve("config.yml")));
    }
    @Test void invalidExistingJsonCannotBeSilentlyTreatedAsAbsent()throws Exception {
        Path path=config("renderer-mode: TEXTURED\nrender-distance: 64\n");byte[] original=Files.readAllBytes(path);Files.writeString(root.resolve("render-v2.json"),"shot:\n  distance: 64");
        assertThrows(IllegalArgumentException.class,()->UnifiedRendererConfig.load(root,defaults()));assertArrayEquals(original,Files.readAllBytes(path));
    }
    @Test void malformedDuplicateQuotedAndFractionalNumbersAreNotCoerced()throws Exception {
        for(String yaml:List.of("render-distance: 64\nrender-distance: 32\n","render-distance: '64'\n","render-distance: 64.5\n","render-v2: null\n","render-v2:\n  preparation:\n    maximumInstances: 8192.5\n","render-v2:\n  pipeline:\n    workers: '2'\n")) {
            Path path=config(yaml);byte[] before=Files.readAllBytes(path);assertThrows(Exception.class,()->UnifiedRendererConfig.load(root,defaults()));assertArrayEquals(before,Files.readAllBytes(path));
        }
    }
    @Test void failedAtomicSaveKeepsOriginalAndLeavesReadableBackup()throws Exception {
        Path path=config("render-distance: 32\n");byte[] original=Files.readAllBytes(path);
        assertThrows(java.io.IOException.class,()->UnifiedRendererConfig.load(root,defaults(),checkpoint->{if(checkpoint==UnifiedRendererConfig.Checkpoint.BEFORE_REPLACE)throw new java.io.IOException("injected");}));
        assertArrayEquals(original,Files.readAllBytes(path));try(var files=Files.list(root)){var backup=files.filter(p->p.toString().endsWith(".bak")).findFirst().orElseThrow();assertArrayEquals(original,Files.readAllBytes(backup));}
    }
    @Test void concurrentHumanConfigEditIsNotOverwrittenByMigration()throws Exception {
        Path path=config("render-distance: 32\n");assertThrows(java.io.IOException.class,()->UnifiedRendererConfig.load(root,defaults(),checkpoint->{if(checkpoint==UnifiedRendererConfig.Checkpoint.BEFORE_REPLACE)Files.writeString(path,"render-distance: 48\n");}));
        assertEquals("render-distance: 48\n",Files.readString(path));
    }

    @Test void schemaOneSnapshotDefaultsUpgradeOnceWithoutRereadingOldJson()throws Exception {
        config("""
                render-v2-schema: 1
                renderer-mode: TEXTURED
                render-distance: 64
                render-v2:
                  capture:
                    maximumSnapshotBytes: 268435456
                    maxChunksPerRequest: 128
                """);
        Files.writeString(root.resolve("render-v2.json"),"bad legacy file must not be imported again");
        var upgraded=UnifiedRendererConfig.load(root,defaults());
        assertEquals(402653184,upgraded.renderer().capture().maximumSnapshotBytes());assertEquals(384,upgraded.renderer().capture().maxChunksPerRequest());
        assertEquals(11,upgraded.yaml().getInt("render-v2-schema"));byte[] first=Files.readAllBytes(root.resolve("config.yml"));
        var twice=UnifiedRendererConfig.load(root,defaults());assertTrue(twice.changes().isEmpty());assertArrayEquals(first,Files.readAllBytes(root.resolve("config.yml")));
    }

    @Test void totalMemoryCannotBeConfiguredAboveTheUser512MiBCap()throws Exception {
        Path path=config("""
                render-v2-schema: 2
                render-distance: 64
                render-v2:
                  memory:
                    maximumBytes: 1073741824
                    occlusion: true
                """);
        byte[] original=Files.readAllBytes(path);assertThrows(RuntimeException.class,()->UnifiedRendererConfig.load(root,defaults()));assertArrayEquals(original,Files.readAllBytes(path));
    }

    @Test void schemaThreeTemplateGetsFasterCpuOnceButCustomBudgetsStayExplicit()throws Exception {
        for(boolean custom:new boolean[]{false,true}) {
            config("""
                render-v2-schema: 3
                render-distance: 64
                render-v2:
                  pipeline:
                    cpuMillisPerSecond: %d
                    burstMillis: %d
                    dispatchIntervalNanos: %d
                """.formatted(custom?777:200,custom?60:25,custom?3000000:5000000));
            var loaded=UnifiedRendererConfig.load(root,defaults());var pipeline=loaded.renderer().pipeline();
            assertEquals(custom?777:2000,pipeline.cpuMillisPerSecond());assertEquals(custom?60:100,pipeline.burstMillis());assertEquals(custom?3000000:1000000,pipeline.dispatchIntervalNanos());
            assertEquals(120000000000L,pipeline.timeoutNanos());assertEquals(2,pipeline.workers());assertEquals(11,loaded.yaml().getInt("render-v2-schema"));
            byte[] first=Files.readAllBytes(root.resolve("config.yml"));assertTrue(UnifiedRendererConfig.load(root,defaults()).changes().isEmpty());assertArrayEquals(first,Files.readAllBytes(root.resolve("config.yml")));
        }
    }


    @Test void schemaFourDualLaneDefaultsDoNotIncreasePhotoAdmissionOrResetCustomSettings()throws Exception {
        config("""
            render-v2-schema: 4
            render-distance: 64
            render-v2:
              pipeline:
                workers: 1
                maxJobs: 1
                perPlayer: 1
                cpuMillisPerSecond: 1000
                burstMillis: 50
            """);
        var policy=UnifiedRendererConfig.load(root,defaults()).renderer().pipeline();assertEquals(2,policy.workers());assertEquals(1,policy.maxJobs());assertEquals(2000,policy.cpuMillisPerSecond());assertEquals(100,policy.burstMillis());
        config("""
            render-v2-schema: 4
            render-distance: 48
            render-v2:
              pipeline:
                workers: 3
                maxJobs: 1
                perPlayer: 1
                cpuMillisPerSecond: 1750
                burstMillis: 80
            """);
        var loaded=UnifiedRendererConfig.load(root,defaults());policy=loaded.renderer().pipeline();assertEquals(3,policy.workers());assertEquals(1750,policy.cpuMillisPerSecond());assertEquals(80,policy.burstMillis());assertEquals(1,policy.maxJobs());assertEquals(48,loaded.renderer().shot().distance());
    }

    @Test void explicitSingleWorkerWithCustomCpuProfileIsNotSilentlyMadeDualLane()throws Exception {
        config("""
            render-v2-schema: 4
            render-distance: 64
            render-v2:
              pipeline:
                workers: 1
                maxJobs: 1
                cpuMillisPerSecond: 777
                burstMillis: 60
            """);
        var policy=UnifiedRendererConfig.load(root,defaults()).renderer().pipeline();assertEquals(1,policy.workers());assertEquals(777,policy.cpuMillisPerSecond());assertEquals(60,policy.burstMillis());assertEquals(1,policy.maxJobs());
    }

    @Test void bundledInstallAndKnownMissingOldDefaultMigrateButRealOrCustomSourcesRemain()throws Exception {
        var fresh=UnifiedRendererConfig.load(root,defaults());assertTrue(fresh.renderer().bundledBase());assertEquals(2,fresh.renderer().pipeline().workers());assertEquals(10000000L,fresh.renderer().pipeline().scanBatchCpuNanos());assertTrue(defaults().contains("# 如果你不清楚这是什么，请不要修改参数。"));
        config("""
            render-v2-schema: 6
            render-distance: 64
            render-v2:
              baseResources: resources/base-client.jar
            """);
        assertTrue(UnifiedRendererConfig.load(root,defaults()).renderer().bundledBase());
        Path resources=Files.createDirectories(root.resolve("resources"));Files.writeString(resources.resolve("base-client.jar"),"user-owned-source-preserve");
        config("""
            render-v2-schema: 6
            render-distance: 32
            render-v2:
              baseResources: resources/base-client.jar
            """);
        assertEquals("resources/base-client.jar",UnifiedRendererConfig.load(root,defaults()).renderer().baseResources());
        config("""
            render-v2-schema: 6
            render-distance: 48
            render-v2:
              baseResources: private/missing-custom.jar
              pipeline:
                maxBatchCpuNanos: 2000000
                burstMillis: 5
            """);
        var custom=UnifiedRendererConfig.load(root,defaults()).renderer();assertEquals("private/missing-custom.jar",custom.baseResources());assertEquals(2000000L,custom.pipeline().scanBatchCpuNanos());assertEquals(48,custom.shot().distance());
    }

    @Test void olderCustomSingleChunkSnapshotBudgetIsNotMadeInvalidOrEnlargedByWindowMigration()throws Exception {
        config("""
            render-v2-schema: 9
            render-distance: 64
            render-v2:
              capture:
                maxChunksPerRequest: 1
                bytesPerChunk: 2097152
                maximumSnapshotBytes: 2097152
            """);
        var result=UnifiedRendererConfig.load(root,defaults());assertEquals(1,result.renderer().capture().windowChunks());assertEquals(2097152,result.renderer().capture().maximumSnapshotBytes());assertEquals(1,result.renderer().capture().maxChunksPerRequest());
        assertTrue(UnifiedRendererConfig.load(root,defaults()).changes().isEmpty());
    }
    @Test void schemaTenVisualTemplateDoublesOnceButEachCustomFieldIsPreserved()throws Exception {
        for(int[] pair:new int[][]{{50000,67108864},{70000,67108864},{50000,33554432},{150000,268435456}}) {
            Path original=config("""
                render-v2-schema: 10
                render-distance: 48
                render-v2:
                  visuals:
                    maximumStates: %d
                    maximumFrozenBytes: %d
                """.formatted(pair[0],pair[1]));
            var result=UnifiedRendererConfig.load(root,defaults());assertEquals(pair[0]==50000?100000:pair[0],result.renderer().visuals().maximumStates());assertEquals(pair[1]==67108864?134217728:pair[1],result.renderer().visuals().maximumFrozenBytes());
            assertEquals(11,result.yaml().getInt("render-v2-schema"));assertEquals(48,result.renderer().shot().distance());assertEquals(536870912,result.renderer().memory().maximumBytes());
            byte[] after=Files.readAllBytes(original);assertTrue(UnifiedRendererConfig.load(root,defaults()).changes().isEmpty());assertArrayEquals(after,Files.readAllBytes(original));
        }
    }
    @Test void freshVisualLimitsAreCeilingsNotARequestForExtraGlobalMemory()throws Exception {
        var result=UnifiedRendererConfig.load(root,defaults());assertEquals(100000,result.renderer().visuals().maximumStates());assertEquals(134217728,result.renderer().visuals().maximumFrozenBytes());assertEquals(536870912,result.renderer().memory().maximumBytes());assertEquals(2,result.renderer().pipeline().workers());
    }

}
