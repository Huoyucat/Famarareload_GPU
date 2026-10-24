package com.wenkrang.famara.render.gpu;

import com.google.gson.*;
import com.wenkrang.famara.render.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.wenkrang.famara.render.output.DiskPhotoStore;
import com.wenkrang.famara.render.schedule.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuConfigurationTest {
    @TempDir Path folder;
    private JsonObject example()throws Exception{return JsonParser.parseString(Files.readString(Path.of("docs/render-v2/runtime-experiment.json"))).getAsJsonObject();}
    private JsonObject gpu(){return new Gson().toJsonTree(GpuConfiguration.disabled()).getAsJsonObject();}
    @Test void legacyJsonWithoutGpuRemainsExplicitlyOff()throws Exception {
        var parsed=TexturedConfiguration.parse(example());assertEquals(GpuConfiguration.disabled(),parsed.gpu());
    }
    @Test void partialUnknownAndUnboundedGpuSettingsFailInsteadOfEnablingNativeCode()throws Exception {
        for(String key:new String[]{"mode","vramBudgetMiB","maxConcurrentJobs","stagingMemoryMiB","textureCacheMiB","kernelTimeoutMillis","minEstimatedSpeedup"}) {
            var root=example();var settings=gpu();settings.remove(key);root.add("gpu",settings);assertThrows(RuntimeException.class,()->TexturedConfiguration.parse(root),key);
        }
        for(String patch:new String[]{"{\"mode\":\"CUDA\"}","{\"mode\":null}","{\"vramBudgetMiB\":0}","{\"maxConcurrentJobs\":2}","{\"textureCacheMiB\":385}","{\"stagingMemoryMiB\":-1}","{\"kernelTimeoutMillis\":60001}","{\"fallbackToCpu\":\"true\"}","{\"minEstimatedSpeedup\":\"1.2\"}","{\"minEstimatedSpeedup\":0.9}","{\"typo\":1}"}) {
            var root=example();var settings=gpu();JsonParser.parseString(patch).getAsJsonObject().entrySet().forEach(e->settings.add(e.getKey(),e.getValue()));root.add("gpu",settings);
            assertThrows(RuntimeException.class,()->TexturedConfiguration.parse(root),patch);
        }
    }
    @Test void yamlAddsDisabledPolicyAtomicallyAndPreservesExplicitSelectionIdempotently()throws Exception {
        Path file=folder.resolve("config.yml");Files.writeString(file,"render-v2-schema: 11\nrenderer-mode: LEGACY\nrender-distance: 32\nrender-v2:\n  gpu:\n    mode: OPENCL\n    fallbackToCpu: false\n");
        String defaults=Files.readString(Path.of("src/main/resources/config.yml"));
        var first=UnifiedRendererConfig.load(folder,defaults);
        assertEquals(PhotoRuntime.Mode.LEGACY,first.mode());assertEquals(32,first.renderer().shot().distance());
        assertEquals(GpuConfiguration.Mode.OPENCL,first.renderer().gpu().mode());assertFalse(first.renderer().gpu().fallbackToCpu());
        assertEquals(384,first.renderer().gpu().vramBudgetMiB());byte[] once=Files.readAllBytes(file);
        var second=UnifiedRendererConfig.load(folder,defaults);assertTrue(second.changes().isEmpty());assertArrayEquals(once,Files.readAllBytes(file));
        try(var files=Files.list(folder)){assertEquals(1,files.filter(p->p.toString().endsWith(".bak")).count());}
    }
    @Test void forcedUnavailableBackendWithoutFallbackIsRejectedBeforePreparationOrOutput()throws Exception {
        var settings=GpuConfiguration.disabled();
        var forced=new GpuConfiguration(GpuConfiguration.Mode.OPENCL,settings.preferredDevice(),settings.vramBudgetMiB(),1,settings.stagingMemoryMiB(),settings.textureCacheMiB(),settings.kernelTimeoutMillis(),false,settings.minEstimatedSpeedup());
        var limits=TexturedConfiguration.parse(example()).pipeline();var prepared=new AtomicInteger();var stored=new DiskPhotoStore(folder);
        try(var pipeline=new CpuPhotoPipeline(limits,stored,(file,photo,current)->{throw new AssertionError("Must not publish");},new CpuBudget(2000,100,System::nanoTime),forced)) {
            assertThrows(IllegalStateException.class,()->pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32*1024*1024,()->{prepared.incrementAndGet();throw new AssertionError("Must not capture");}));
            assertEquals(0,prepared.get());assertTrue(stored.committedIds().isEmpty());
        }
    }
}
