package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.*;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.*;
import com.wenkrang.famara.render.output.MapPaletteSnapshot;
import com.wenkrang.famara.render.scene.*;
import com.wenkrang.famara.render.schedule.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际捕获服务+流式转换，owner平台端口模拟，不替代真实Folia运行证据。 */
class StreamingPreparationTest {
    @ParameterizedTest @ValueSource(ints={1,2}) void rawSnapshotsStayBoundedAndOccludedChunksStillCaptureEntityMetadata(int window)throws Exception {
        RenderFixtures fixture=new RenderFixtures();fixture.png("test:tile",1,1,0xffffffff);fixture.cube("test:model","test:tile");fixture.block("minecraft:stone","test:model");
        fixture.json("assets/minecraft/blockstates/air.json","{\"variants\":{\"\":{\"model\":\"test:absent\"}}}");
        var memory=new RenderMemoryBudget(536870912);var cpu=new CpuBudget(5000,100,System::nanoTime);
        try(var repository=new AssetRepository(2);var service=new ChunkCaptureService(new ChunkCaptureService.Limits(2,192,window,window,2097152,402653184,10000000,1000000,120000000000L,window),cpu,memory);
            var sched=mockStatic(Sched.class);var runtime=mockStatic(RuntimeCompat.class)) {
            long id=repository.requestBuild();repository.publish(id,fixture.generation(id));World world=mock(World.class);when(world.getMinHeight()).thenReturn(0);when(world.getMaxHeight()).thenReturn(16);
            when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);Map<String,Integer> copies=new HashMap<>();
            BlockData air=mock(BlockData.class),stone=mock(BlockData.class);when(air.getAsString()).thenReturn("minecraft:air");when(stone.getAsString()).thenReturn("minecraft:stone");
            Map<String,Chunk> chunks=new HashMap<>();Map<Chunk,ChunkSnapshot> snapshots=new IdentityHashMap<>();
            when(world.getChunkAt(anyInt(),anyInt())).thenAnswer(call->{int cx=call.getArgument(0),cz=call.getArgument(1);return chunks.computeIfAbsent(cx+","+cz,key->{
                Chunk chunk=mock(Chunk.class);ChunkSnapshot snapshot=mock(ChunkSnapshot.class);snapshots.put(chunk,snapshot);
                when(snapshot.getX()).thenReturn(cx);when(snapshot.getZ()).thenReturn(cz);when(snapshot.getBlockSkyLight(anyInt(),anyInt(),anyInt())).thenReturn(15);
                when(snapshot.getBlockData(anyInt(),anyInt(),anyInt())).thenAnswer(position->{int x=cx*16+(int)position.getArgument(0),y=position.getArgument(1),z=cz*16+(int)position.getArgument(2);
                    return z==2 && x>=-8 && x<=8 && y<=8 || z>=16 && x>=-24 && x<=24?stone:air;});return chunk;
            });});
            when(world.getChunkAtAsync(anyInt(),anyInt(),eq(false))).thenAnswer(call->{Chunk chunk=world.getChunkAt((int)call.getArgument(0),(int)call.getArgument(1));return CompletableFuture.completedFuture(chunk);});
            sched.when(()->Sched.owns(eq(world),anyInt(),anyInt())).thenReturn(true);sched.when(()->Sched.region(eq(world),anyInt(),anyInt(),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return null;});
            runtime.when(()->RuntimeCompat.texturedSnapshot(any())).thenAnswer(call->{ChunkSnapshot snapshot=snapshots.get(call.getArgument(0));copies.merge(snapshot.getX()+","+snapshot.getZ(),1,Integer::sum);return snapshot;});
            var input=new CeVisualResourceService.Input(repository.acquire(),new VisualStateTable(CeCompat.visualEpoch(),List.of(),100));
            var camera=new Camera(new Vec3(.5,4,-3),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1);
            var settings=new CpuPhotoRenderer.Settings(40,1,Texture.Filter.NEAREST,false,0,.5,32,0,0,new VoxelTracer.Limits(10000,1000,10000),Map.of());
            var palette=new MapPaletteSnapshot("test",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff)));
            try(var work=new WorldPreparation(service,world,new WorldPreparation.WorldInfo(UUID.randomUUID(),0,16,0),input,camera,settings,palette,new WorldPreparation.Limits(500000,100,20480,196608),
                    new VersionStateAdapter(VersionStateAdapter.Version.parse("26.3"),VersionStateAdapter.Version.parse("26.3")),Set.of(),(s,x,y,z,v)->new SceneSnapshot.Lighting(15,0,0xffffff)).memory(memory).window(window)) {
                int slices=0;long maximumRaw=0;
                while(work.step(2048,Long.MAX_VALUE)!=CpuPhotoPipeline.PreparationStep.DONE) {
                    service.tick();maximumRaw=Math.max(maximumRaw,service.metrics().reservedBytes());assertTrue(++slices<100000);
                }
                assertTrue(maximumRaw<=2097152L*window);assertTrue(copies.values().stream().allMatch(count->count==1));assertEquals(0,service.metrics().reservedBytes());
                var result=work.result();assertTrue(Long.parseLong(result.sourceMetadata().get("occludedHosts"))>0);
                assertTrue(Long.parseLong(result.sourceMetadata().get("fastInterior"))>0);assertTrue(Long.parseLong(result.sourceMetadata().get("scanSlices"))>0);
                var cached=WorldPreparation.class.getDeclaredField("cachedChunk");cached.setAccessible(true);assertNull(cached.get(work),"冻结数据释放后缓存不能继续钉住原块");
                assertTrue(Integer.parseInt(result.sourceMetadata().get("skippedBlockSnapshots"))>0,"石墙后确定不可见块应跳过块快照，但实体元数据仍走owner");
                assertTrue(result.scene().instances().size()<20480);result.lease().close();result.memory().close();
            }
            assertEquals(0,memory.snapshot().live());assertTrue(memory.snapshot().peak()<=536870912);
        }
    }
}
