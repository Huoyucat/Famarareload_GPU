package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.platform.*;
import com.wenkrang.famara.render.schedule.*;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CeVisualCapacityTest {
    @TempDir Path root;
    @AfterEach void reset(){CeCompat.backendForTests(null);}
    private static final AssetLimits ASSETS=new AssetLimits(200000,4194304,134217728,1048576,4096,20000,64,64,200000,64);
    private static final VersionStateAdapter VERSION=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));
    private RenderFixtures fixture()throws Exception {
        var f=new RenderFixtures();f.png("test:tile",2,2,0xffff0000,0xff00ff00,0xff0000ff,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");return f;
    }
    private CeCompat.VisualState row(int i) {
        if(i<80000){int block=i/2;return new CeCompat.VisualState(i%2==0?"craftengine:custom_"+block:"test:logical[variant="+block+"]","minecraft:note_block","test:id_"+(block/16),"minecraft:stone","test:block");}
        return new CeCompat.VisualState("minecraft:oak_leaves[distance="+(i-80000)+"]",null,null,null,"test:block",false);
    }
    private AtomicInteger backend(int count) {
        var n=new AtomicInteger();CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}public CeCompat.VisualCursor visualCursor(){return new CeCompat.VisualCursor(){
            public boolean done(){return n.get()==count;}public List<CeCompat.VisualState> next(int maximum){assertEquals(1,maximum);return List.of(row(n.getAndIncrement()));}
        };}});return n;
    }
    @Test void oneHundredThousandMappingsFitAlongsideProductionBaselineAssetsAndAPhoto()throws Exception {
        var f=fixture();for(var item:f.files.entrySet()){Path file=root.resolve(item.getKey());Files.createDirectories(file.getParent());Files.write(file,item.getValue());}
        var n=backend(100000);var memory=new RenderMemoryBudget(536870912);var budget=new CpuBudget(5000,100,System::nanoTime);long start=System.nanoTime();long tableBytes;
        try(var baseline=memory.reserve("production-baseline",86446080);var repository=new AssetRepository(2);
            var loader=new BudgetedAssetLoader(repository,ASSETS,new BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,60000000000L),budget,memory);
            var background=new BudgetedWorkRunner(1,1000000,1000000,budget);
            var service=new CeVisualResourceService(root,ASSETS,new CeVisualResourceService.Limits(100000,134217728,512,1000000,1000000),repository,loader,background,budget,e->fail(e)).memory(memory)) {
            while(n.get()<100000 && !service.readiness().toCompletableFuture().isDone()){assertTrue(System.nanoTime()-start<60000000000L);service.tick();}
            service.tick();var ready=service.readiness().toCompletableFuture().get(60,TimeUnit.SECONDS);assertEquals(100000,ready.table().size());assertEquals(40000,ready.table().nativeSlotEntries());assertEquals(20000,ready.table().vanillaRemapCount());
            tableBytes=ready.table().estimatedRetainedBytes();assertTrue(tableBytes<134217728);assertFalse(memory.snapshot().domains().containsKey("visual-table-build"));
            try(var photo=memory.reserve("photo-workspace",201326592);var raw=memory.reserve("raw-window",4194304);var input=service.acquire()) {
                for(int i:new int[]{0,1,39998,39999,79998,79999,80000,99999}) {
                    var state=row(i);var resolved=input.table().resolve(CanonicalBlockState.parse(state.serverState()),input.lease().generation(),VERSION);
                    assertEquals("test:block",resolved.descriptor().visualState().toString());assertEquals(state.customBlock()?VisualBlockDescriptor.Kind.CE:VisualBlockDescriptor.Kind.VANILLA,resolved.descriptor().kind());
                    if(state.customBlock())assertEquals(state.id(),resolved.descriptor().logicalId().toString());
                }
                var entry=row(79999);var reference=new VisualStateTable(CeCompat.visualEpoch(),List.of(new VisualStateTable.Entry(CanonicalBlockState.parse(entry.serverState()),ResourceKey.parse(entry.id()),CanonicalBlockState.parse(entry.vanillaState()),CanonicalBlockState.parse(entry.visualState()),ResourceKey.parse(entry.material()))),10);
                int[] image=render(input.table(),entry.serverState(),input.lease().generation());assertArrayEquals(render(reference,entry.serverState(),input.lease().generation()),image);assertTrue(Arrays.stream(image).distinct().count()>2);
                assertTrue(memory.snapshot().live()<=536870912);
                System.out.println("CE_CAPACITY rows="+ready.table().size()+" nativeAliases="+ready.table().nativeSlotEntries()+" remaps="+ready.table().vanillaRemapCount()+" tableBytes="+tableBytes+" liveWithPhotoAndRaw="+memory.snapshot().live()+" peak="+memory.snapshot().peak()+" rgbPixels="+image.length);
            }
        }
        assertEquals(0,memory.snapshot().live());System.out.println("CE_CAPACITY finalLive="+memory.snapshot().live());
    }
    private int[] render(VisualStateTable table,String server,AssetGeneration assets) {
        var descriptor=table.resolve(CanonicalBlockState.parse(server),assets,VERSION).descriptor();var builder=new SceneSnapshot.Builder(assets,0,32,10,10000);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)builder.availableSection(x,y,z);
        var scene=builder.put(0,0,0,descriptor,new SceneSnapshot.Lighting(15,0,0xffffff),0).build();var camera=new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.PERSPECTIVE,Math.PI/3);
        var renderer=new CpuPhotoRenderer(scene,camera,new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,20,0,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()));
        var cursor=renderer.cursor();while(renderer.renderBatch(cursor,256,Long.MAX_VALUE,System::nanoTime,()->false).status()!=CpuPhotoRenderer.Status.DONE){}return cursor.copyRgb();
    }
}
