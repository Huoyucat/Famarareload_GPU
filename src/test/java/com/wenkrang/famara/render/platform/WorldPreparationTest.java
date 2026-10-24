package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.output.MapPaletteSnapshot;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.CpuPhotoPipeline;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldPreparationTest {
    private AssetRepository repository() throws Exception {
        RenderFixtures f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:block/model","test:tile");f.block("minecraft:stone","test:block/model");
        f.json("assets/minecraft/blockstates/air.json","{\"variants\":{\"\":{\"model\":\"test:unused\"}}}");
        AssetRepository repository=new AssetRepository(2);long id=repository.requestBuild();assertTrue(repository.publish(id,f.generation(id)));return repository;
    }
    @Test void waitsForFrozenSnapshotsScansCooperativelyAndTransfersOnlyPureScene() throws Exception {
        try(var assets=repository()) {
            var capture=mock(ChunkCaptureService.class);var request=mock(ChunkCaptureService.Request.class);
            when(request.timings()).thenReturn(new ChunkCaptureService.Timings(0,0,0,0,0,0));when(capture.maximumChunksPerRequest()).thenReturn(16);when(capture.capture(any(),anyList())).thenReturn(request);
            CompletableFuture<ChunkCaptureService.Request> done=new CompletableFuture<>();when(request.result()).thenReturn(done);
            ChunkSnapshot snapshot=mock(ChunkSnapshot.class);BlockData air=mock(BlockData.class),stone=mock(BlockData.class);
            when(air.getAsString()).thenReturn("minecraft:air");when(stone.getAsString()).thenReturn("minecraft:stone");
            when(snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenReturn(air);when(snapshot.getBlockData(0,0,0)).thenReturn(stone);
            Map<ChunkCaptureService.Coordinate,ChunkCaptureService.Captured> chunks=new HashMap<>();
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)chunks.put(new ChunkCaptureService.Coordinate(x,z),new ChunkCaptureService.Captured(snapshot,10,20));
            when(request.snapshots()).thenReturn(chunks);
            var settings=new CpuPhotoRenderer.Settings(2,1,Texture.Filter.NEAREST,false,0,.5,20,0,0,new VoxelTracer.Limits(100,100,1000),Map.of());
            var palette=new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff)));
            var input=new CeVisualResourceService.Input(assets.acquire(),new VisualStateTable(com.wenkrang.famara.compat.CeCompat.visualEpoch(),List.of(),10));
            var version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));
            var camera=new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1);
            AtomicInteger wakes=new AtomicInteger();World world=mock(World.class);
            try(var work=new WorldPreparation(capture,world,new WorldPreparation.WorldInfo(UUID.randomUUID(),0,16,0),input,camera,settings,palette,
                    new WorldPreparation.Limits(1000,10,100,10000),version,Set.of(),(s,x,y,z,v)->new SceneSnapshot.Lighting(15,0,0xffffff))) {
                work.wakeup(wakes::incrementAndGet);
                assertEquals(CpuPhotoPipeline.PreparationStep.WAITING,work.step(5,Long.MAX_VALUE));
                assertEquals(CpuPhotoPipeline.PreparationStep.WAITING,work.step(5,Long.MAX_VALUE));
                done.complete(request);assertEquals(1,wakes.get());
                int slices=0;while(work.step(5,Long.MAX_VALUE)!=CpuPhotoPipeline.PreparationStep.DONE){assertTrue(++slices<100000);}
                assertTrue(slices>10);var prepared=work.result();
                assertFalse(prepared.scene().instances().isEmpty());assertEquals(Long.toString(com.wenkrang.famara.compat.CeCompat.visualEpoch()),prepared.sourceMetadata().get("visualEpoch"));
                verify(request,atLeastOnce()).close();verifyNoInteractions(world);
                work.close();assertSame(prepared.scene().assets(),prepared.lease().generation());prepared.lease().close();
            }
        }
    }
    @Test void relativeSectionIndexSupportsNegativeWorldHeights() {
        assertEquals(0,WorldPreparation.sectionIndex(-64,-64));assertEquals(3,WorldPreparation.sectionIndex(-1,-64));
        assertEquals(4,WorldPreparation.sectionIndex(0,-64));assertEquals(23,WorldPreparation.sectionIndex(319,-64));
    }

    @Test void blocksEntirelyBehindCameraDoNotConsumeVisibleInstanceBudget()throws Exception {
        try(var assets=repository()) {
            var capture=mock(ChunkCaptureService.class);var request=mock(ChunkCaptureService.Request.class);
            when(request.timings()).thenReturn(new ChunkCaptureService.Timings(0,0,0,0,0,0));when(capture.maximumChunksPerRequest()).thenReturn(16);when(capture.capture(any(),anyList())).thenReturn(request);
            when(request.result()).thenReturn(CompletableFuture.completedFuture(request));
            ChunkSnapshot snapshot=mock(ChunkSnapshot.class);BlockData air=mock(BlockData.class),stone=mock(BlockData.class);
            when(air.getAsString()).thenReturn("minecraft:air");when(stone.getAsString()).thenReturn("minecraft:stone");
            when(snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(call->{int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);return x==15 || x==2 && y==0 && z==0?stone:air;});
            Map<ChunkCaptureService.Coordinate,ChunkCaptureService.Captured> chunks=new HashMap<>();
            for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)chunks.put(new ChunkCaptureService.Coordinate(x,z),new ChunkCaptureService.Captured(snapshot,10,20));
            when(request.snapshots()).thenReturn(chunks);
            var settings=new CpuPhotoRenderer.Settings(2,1,Texture.Filter.NEAREST,false,0,.5,20,0,0,new VoxelTracer.Limits(100,100,1000),Map.of());
            var palette=new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff)));
            var input=new CeVisualResourceService.Input(assets.acquire(),new VisualStateTable(com.wenkrang.famara.compat.CeCompat.visualEpoch(),List.of(),10));
            var version=new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3"));
            try(var work=new WorldPreparation(capture,mock(World.class),new WorldPreparation.WorldInfo(UUID.randomUUID(),0,16,0),input,
                    new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1),settings,palette,new WorldPreparation.Limits(1000,10,1,10000),version,Set.of(),
                    (chunk,x,y,z,state)->new SceneSnapshot.Lighting(15,0,0xffffff))) {
                int steps=0;while(work.step(5,Long.MAX_VALUE)!=CpuPhotoPipeline.PreparationStep.DONE)assertTrue(++steps<100000);
                var prepared=work.result();assertEquals(1,prepared.scene().instances().size());assertEquals(2,prepared.scene().instances().getFirst().x());
                assertTrue(Long.parseLong(prepared.sourceMetadata().get("culledHosts"))>0);prepared.lease().close();
            }
        }
    }

    @Test void distanceSixtyFourCapturesOnlyFrustumChunksPlusOneChunkHalo() throws Exception {
        try(var assets=repository()) {
            var capture=mock(ChunkCaptureService.class);var request=mock(ChunkCaptureService.Request.class);var pending=new CompletableFuture<ChunkCaptureService.Request>();
            when(request.timings()).thenReturn(new ChunkCaptureService.Timings(0,0,0,0,0,0));when(capture.maximumChunksPerRequest()).thenReturn(256);when(capture.capture(any(),anyList())).thenReturn(request);when(request.result()).thenReturn(pending);
            var input=new CeVisualResourceService.Input(assets.acquire(),new VisualStateTable(com.wenkrang.famara.compat.CeCompat.visualEpoch(),List.of(),100));
            var camera=new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1);
            var settings=new CpuPhotoRenderer.Settings(64,1,Texture.Filter.NEAREST,false,0,.5,10,0,0,new VoxelTracer.Limits(100,100,1000),Map.of());
            try(var work=new WorldPreparation(capture,mock(World.class),new WorldPreparation.WorldInfo(UUID.randomUUID(),0,16,0),input,camera,settings,new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff))),new WorldPreparation.Limits(500000,10,10,10000),new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3")),Set.of(),(chunk,x,y,z,state)->new SceneSnapshot.Lighting(15,0,0xffffff))) {
                assertEquals(CpuPhotoPipeline.PreparationStep.WAITING,work.step(1,Long.MAX_VALUE));
                var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(capture).capture(any(),captor.capture());int size=captor.getValue().size();assertTrue(size>0 && size<81,"候选区块="+size);work.close();
            }
        }
    }

    @Test void failedTableAndExtrasCloseStillReturnTheWorkspaceAndInputLease()throws Exception {
        try(var assets=repository()) {
            var global=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(536870912);var capture=mock(ChunkCaptureService.class);var request=mock(ChunkCaptureService.Request.class);when(request.timings()).thenReturn(new ChunkCaptureService.Timings(0,0,0,0,0,0));when(capture.maximumChunksPerRequest()).thenReturn(192);when(capture.capture(any(),anyList(),any())).thenReturn(request);when(request.result()).thenReturn(new CompletableFuture<>());
            var input=new CeVisualResourceService.Input(assets.acquire(),new VisualStateTable(com.wenkrang.famara.compat.CeCompat.visualEpoch(),List.of(),100),()->{throw new IllegalStateException("table close");});
            var camera=new Camera(new Vec3(.5,.5,.5),0,0,Camera.Projection.LEGACY_ANGULAR,1);var settings=new CpuPhotoRenderer.Settings(2,1,Texture.Filter.NEAREST,false,0,.5,32,0,0,new VoxelTracer.Limits(100,100,1000),Map.of());
            var work=new WorldPreparation(capture,mock(World.class),new WorldPreparation.WorldInfo(UUID.randomUUID(),0,16,0),input,camera,settings,new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff))),new WorldPreparation.Limits(1000,100,10,10000),new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3")),Set.of(),(c,x,y,z,v)->new SceneSnapshot.Lighting(15,0,0xffffff)).memory(global);
            class BrokenExtras implements ChunkCaptureService.Extras,AutoCloseable {public List<LegacyEntity> capture(Chunk chunk){return List.of();}public void close(){throw new IllegalStateException("extras close");}}
            work.extras(new BrokenExtras());assertEquals(CpuPhotoPipeline.PreparationStep.WAITING,work.step(10,Long.MAX_VALUE));assertTrue(global.snapshot().live()>0);assertTrue(global.snapshot().live()<201326592);
            var error=assertThrows(IllegalStateException.class,work::close);assertEquals("table close",error.getCause().getMessage());assertEquals(1,error.getCause().getSuppressed().length);assertEquals(0,global.snapshot().live());work.close();verify(request).close();
        }
    }
}
