package com.wenkrang.famara.render.asset;

import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.scene.*;
import org.junit.jupiter.api.Test;
import com.wenkrang.famara.render.schedule.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class LargeZipIndexTest {
    @TempDir Path root;
    static final AssetLimits PRODUCTION=new AssetLimits(200000,4194304,134217728,1048576,4096,20000,64,64,200000,64);
    static RenderFixtures fixture()throws Exception {
        var f=new RenderFixtures();f.png("nameplates:font/nameplates/actually_used",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);
        f.cube("bettermodel:entity/shared/parent","nameplates:font/nameplates/actually_used");
        f.json("assets/test/models/child.json","{\"parent\":\"bettermodel:entity/shared/parent\"}");f.block("test:block","test:child");
        f.json("assets/nameplates/textures/font/nameplates/actually_used.png.mcmeta","{\"animation\":{\"frametime\":2,\"frames\":[0]}}");return f;
    }
    @Test void hundredThousandIrrelevantPathsDoNotConsumeTheOld64MiBStringIndexes()throws Exception {
        var f=fixture();Path zip=root.resolve("large-merged.zip");long oldPathBytes=2048;
        try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
            for(var entry:f.files.entrySet()){output.putNextEntry(new ZipEntry(entry.getKey()));output.write(entry.getValue());output.closeEntry();oldPathBytes+=608L+entry.getKey().length()*8L;}
            for(int i=0;i<100000;i++) {
                String name=i%2==0?"assets/nameplates/textures/font/nameplates/production_unused_icon_"+i+".png":"assets/bettermodel/models/entity/generated/production_unused_model_"+i+".json";
                output.putNextEntry(new ZipEntry(name));output.write(0);output.closeEntry();oldPathBytes+=608L+name.length()*8L;
            }
        }
        long oldEstimate=oldPathBytes+ZipDirectoryBudget.estimatedBytes(zip);assertTrue(oldEstimate>67108864);
        ResourcePack selected;BlockResourceCursor.Metrics metrics;
        try(var cursor=new BlockResourceCursor(List.of(new BlockResourceCursor.Input(zip,ResourcePack.Role.CUSTOM,true,null)),PRODUCTION,1073741824)) {
            while(!cursor.step(128,Long.MAX_VALUE,System::nanoTime)){}selected=cursor.result();metrics=cursor.metrics();
        }
        var memory=new RenderMemoryBudget(536870912);
        try(var repository=new AssetRepository(2);var loader=new BudgetedAssetLoader(repository,PRODUCTION,new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,30000000000L,1073741824L),new CpuBudget(5000,100,System::nanoTime),memory)) {
            loader.reloadSources(List.of(new BudgetedAssetLoader.Source(zip,ResourcePack.Role.CUSTOM,true)),()->true).toCompletableFuture().get(30,TimeUnit.SECONDS);
            try(var lease=repository.acquire()){assertTrue(lease.generation().issues().isEmpty());assertEquals(1,lease.generation().completeBlockDefinitions());assertArrayEquals(render(f.generation(1)),render(lease.generation()));}
        }
        assertEquals(0,memory.snapshot().live());assertTrue(memory.snapshot().peak()<=536870912);
        Files.delete(zip);assertEquals(f.files.keySet(),selected.paths());assertEquals(f.files.size()+100000,metrics.indexedEntries());assertEquals(f.files.size(),metrics.retainedPaths());assertEquals(f.files.size(),metrics.selectedEntries());
        assertTrue(metrics.indexPeakBytes()<67108864);assertEquals(f.files.values().stream().mapToLong(a->a.length).sum(),metrics.selectedBytes());
        for(var entry:f.files.entrySet())assertArrayEquals(entry.getValue(),selected.require(entry.getKey()).copyBytes());
        var actual=AssetGeneration.compile(1,selected,PRODUCTION);var reference=f.generation(1);
        assertEquals(reference.resolve(RenderFixtures.vanilla("test:block",1),0,0,0,0).getFirst().quads(),actual.resolve(RenderFixtures.vanilla("test:block",1),0,0,0,0).getFirst().quads());
        int[] rgb=render(actual);assertArrayEquals(render(reference),rgb);assertTrue(Arrays.stream(rgb).distinct().count()>2);
        System.out.println("LARGE_ZIP scanned="+metrics.indexedEntries()+" retained="+metrics.retainedPaths()+" selectedBytes="+metrics.selectedBytes()+" indexPeak="+metrics.indexPeakBytes()+" oldEstimate="+oldEstimate+" rgbPixels="+rgb.length);
    }
    private int[] render(AssetGeneration assets) {
        var builder=new SceneSnapshot.Builder(assets,0,32,10,10000);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)builder.availableSection(x,y,z);
        builder.put(0,0,0,new VisualBlockDescriptor(VisualBlockDescriptor.Kind.CE,ResourceKey.parse("test:panel"),CanonicalBlockState.parse("test:block"),assets.id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
        var scene=builder.build();var camera=new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.PERSPECTIVE,Math.PI/3);
        var renderer=new CpuPhotoRenderer(scene,camera,new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,20,0,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()));
        var cursor=renderer.cursor();while(renderer.renderBatch(cursor,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}return cursor.copyRgb();
    }
}
