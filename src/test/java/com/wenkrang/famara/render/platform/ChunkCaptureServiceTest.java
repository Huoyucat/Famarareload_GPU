package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.RuntimeCompat;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.schedule.CpuBudget;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChunkCaptureServiceTest {
    private final ChunkCaptureService.Limits limits=new ChunkCaptureService.Limits(2,2,1,1,2*1024*1024,4*1024*1024,10_000_000,1_000_000,10_000_000_000L);
    private CpuBudget budget(){return new CpuBudget(5000,100,System::nanoTime);}
    @Test void existingOnlyLoadsAreBoundedAndSnapshotRunsOnOwner() throws Exception {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);ChunkSnapshot snapshot=mock(ChunkSnapshot.class);
        CompletableFuture<Chunk> loading=new CompletableFuture<>();List<Runnable> owners=new ArrayList<>();
        when(world.getChunkAtAsync(0,0,false)).thenReturn(loading);when(world.isChunkLoaded(0,0)).thenReturn(true);
        when(world.getChunkAt(0,0)).thenReturn(chunk);when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        CpuBudget budget=budget();
        try(MockedStatic<Sched> sched=mockStatic(Sched.class);MockedStatic<RuntimeCompat> runtime=mockStatic(RuntimeCompat.class);
            var service=new ChunkCaptureService(limits,budget)) {
            sched.when(()->Sched.region(eq(world),eq(0),eq(0),any())).thenAnswer(call->{owners.add(call.getArgument(3));return null;});
            sched.when(()->Sched.owns(world,0,0)).thenReturn(false);runtime.when(()->RuntimeCompat.texturedSnapshot(chunk)).thenReturn(snapshot);
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0)));
            service.tick();service.tick();verify(world,times(1)).getChunkAtAsync(0,0,false);
            assertEquals(1,service.metrics().inflight());loading.complete(chunk);
            assertFalse(request.result().toCompletableFuture().isDone());assertEquals(1,owners.size());
            sched.when(()->Sched.owns(world,0,0)).thenReturn(true);owners.removeFirst().run();assertSame(request,request.result().toCompletableFuture().get(2,TimeUnit.SECONDS));
            assertSame(snapshot,request.snapshots().values().iterator().next().snapshot());
            assertEquals(0,service.metrics().inflight());assertEquals(2*1024*1024,service.metrics().reservedBytes());
            request.close();assertEquals(0,service.metrics().reservedBytes());assertTrue(budget.snapshot().settledBatches()>=2);
            verify(world,never()).getChunkAtAsync(anyInt(),anyInt(),eq(true));
        }
    }
    @Test void unloadedOrUngeneratedChunksFailRatherThanBecomeAir() {
        World world=mock(World.class);when(world.getChunkAtAsync(0,0,false)).thenReturn(CompletableFuture.completedFuture(null));
        try(var service=new ChunkCaptureService(limits,budget())) {
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0)));service.tick();
            assertThrows(CompletionException.class,()->request.result().toCompletableFuture().join());
            assertEquals(0,service.metrics().reservedBytes());verify(world,never()).getChunkAt(anyInt(),anyInt());
        }
    }
    @Test void closedRequestDiscardsLateLoadAndReturnsMemoryWithoutCreatingSnapshot() {
        World world=mock(World.class);CompletableFuture<Chunk> loading=new CompletableFuture<>();
        when(world.getChunkAtAsync(0,0,false)).thenReturn(loading);
        try(var service=new ChunkCaptureService(limits,budget())) {
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0)));service.tick();request.close();
            assertEquals(0,service.metrics().reservedBytes());loading.complete(mock(Chunk.class));
            assertEquals(0,service.metrics().inflight());verify(world,never()).getChunkAt(anyInt(),anyInt());
        }
    }

    @Test void approvedSnapshotDefaultsAllow128ChunksButAllRequestsShare256MiB() {
        var limits=new ChunkCaptureService.Limits(2,128,1,1,2097152,268435456,1000000,1000000,10000000000L);
        var coordinates=new ArrayList<ChunkCaptureService.Coordinate>();for(int i=0;i<128;i++)coordinates.add(new ChunkCaptureService.Coordinate(i,0));
        World world=mock(World.class);
        try(var service=new ChunkCaptureService(limits,budget())) {
            var one=service.capture(world,coordinates);assertEquals(268435456,service.metrics().reservedBytes());
            assertThrows(RejectedExecutionException.class,()->service.capture(world,List.of(new ChunkCaptureService.Coordinate(200,0))));
            one.close();assertEquals(0,service.metrics().reservedBytes());coordinates.add(new ChunkCaptureService.Coordinate(128,0));
            assertThrows(IllegalArgumentException.class,()->service.capture(world,coordinates));
        }
    }

    @Test void snapshot384MiBAllows192ChunksAndStillBoundsAllRequests() {
        var limits=new ChunkCaptureService.Limits(2,192,1,1,2097152,402653184,1000000,1000000,10000000000L);
        var coordinates=new ArrayList<ChunkCaptureService.Coordinate>();for(int i=0;i<192;i++)coordinates.add(new ChunkCaptureService.Coordinate(i,0));
        try(var service=new ChunkCaptureService(limits,budget())) {
            World world=mock(World.class);var one=service.capture(world,coordinates);assertEquals(402653184,service.metrics().reservedBytes());
            assertThrows(RejectedExecutionException.class,()->service.capture(world,List.of(new ChunkCaptureService.Coordinate(200,0))));one.close();assertEquals(0,service.metrics().reservedBytes());
            coordinates.add(new ChunkCaptureService.Coordinate(192,0));assertThrows(IllegalArgumentException.class,()->service.capture(world,coordinates));
        }
    }

    @Test void loadedOwnerCapturesWithoutAsyncLoadOrAnotherRegionHop() {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);ChunkSnapshot snapshot=mock(ChunkSnapshot.class);
        when(world.isChunkLoaded(0,0)).thenReturn(true);when(world.getChunkAt(0,0)).thenReturn(chunk);when(world.getMaxHeight()).thenReturn(16);
        try(var sched=mockStatic(Sched.class);var runtime=mockStatic(RuntimeCompat.class);var service=new ChunkCaptureService(limits,budget())) {
            sched.when(()->Sched.owns(world,0,0)).thenReturn(true);runtime.when(()->RuntimeCompat.texturedSnapshot(chunk)).thenReturn(snapshot);
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0)));service.tick();
            assertTrue(request.result().toCompletableFuture().isDone());assertSame(snapshot,request.snapshots().values().iterator().next().snapshot());
            verify(world,never()).getChunkAtAsync(anyInt(),anyInt(),anyBoolean());sched.verify(()->Sched.region(eq(world),anyInt(),anyInt(),any()),never());request.close();
        }
    }


    @Test void twoChunkRequestConsumesOnlyAfterCompletionAndReturnsEachReservationExactlyOnce()throws Exception {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);ChunkSnapshot snapshot=mock(ChunkSnapshot.class);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);when(world.getMaxHeight()).thenReturn(16);
        var global=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(536870912);var policy=new ChunkCaptureService.Limits(2,384,2,2,2097152,402653184,1000000000,1000000,10000000000L,2);
        try(var sched=mockStatic(Sched.class);var runtime=mockStatic(RuntimeCompat.class);var service=new ChunkCaptureService(policy,budget(),global)) {
            sched.when(()->Sched.owns(eq(world),anyInt(),anyInt())).thenReturn(true);runtime.when(()->RuntimeCompat.texturedSnapshot(chunk)).thenReturn(snapshot);var a=new ChunkCaptureService.Coordinate(0,0);var b=new ChunkCaptureService.Coordinate(1,0);var request=service.capture(world,List.of(a,b));assertEquals(4194304,global.snapshot().live());assertThrows(IllegalStateException.class,()->request.consume(a));
            service.tick();assertTrue(request.result().toCompletableFuture().isDone());assertEquals(2,request.snapshots().size());request.consume(a);assertEquals(2097152,request.reservedBytes());assertEquals(2097152,global.snapshot().live());assertEquals(1,request.snapshots().size());assertThrows(IllegalStateException.class,()->request.consume(a));request.consume(b);assertEquals(0,global.snapshot().live());assertEquals(0,service.metrics().reservedBytes());request.close();request.close();assertEquals(0,service.metrics().inflight());assertThrows(IllegalStateException.class,()->request.consume(b));
        }
    }
    @Test void cancellingTwoPendingAsyncLoadsReleasesSlotsBeforeTheirLateCallbacks() {
        World world=mock(World.class);CompletableFuture<Chunk> a=new CompletableFuture<>(),b=new CompletableFuture<>();when(world.getChunkAtAsync(0,0,false)).thenReturn(a);when(world.getChunkAtAsync(1,0,false)).thenReturn(b);var global=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(536870912);var policy=new ChunkCaptureService.Limits(2,384,2,2,2097152,402653184,1000000000,1000000,10000000000L,2);
        try(var sched=mockStatic(Sched.class);var service=new ChunkCaptureService(policy,budget(),global)) {
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0),new ChunkCaptureService.Coordinate(1,0)));service.tick();assertEquals(2,service.metrics().inflight());request.close();assertEquals(0,service.metrics().inflight());assertEquals(0,global.snapshot().live());a.complete(mock(Chunk.class));b.complete(mock(Chunk.class));assertEquals(0,service.metrics().inflight());assertEquals(0,service.metrics().reservedBytes());verify(world,never()).getChunkAt(anyInt(),anyInt());
        }
    }

    @Test void closingRequestWithAnActiveOwnerRetainsRawUntilThatOwnerExits()throws Exception {
        World world=mock(World.class);Chunk chunk=mock(Chunk.class);when(world.getMaxHeight()).thenReturn(16);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);when(world.getChunkAtAsync(anyInt(),anyInt(),eq(false))).thenReturn(CompletableFuture.completedFuture(chunk));
        var global=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(536870912);var policy=new ChunkCaptureService.Limits(2,384,2,2,2097152,402653184,1000000000,1000000,10000000000L,2);List<Runnable> owners=new ArrayList<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var sched=mockStatic(Sched.class);var runtime=mockStatic(RuntimeCompat.class);var service=new ChunkCaptureService(policy,budget(),global);var pool=Executors.newSingleThreadExecutor()) {
            sched.when(()->Sched.owns(eq(world),anyInt(),anyInt())).thenReturn(false);sched.when(()->Sched.region(eq(world),anyInt(),anyInt(),any())).thenAnswer(call->{owners.add(call.getArgument(3));return null;});
            var request=service.capture(world,List.of(new ChunkCaptureService.Coordinate(0,0),new ChunkCaptureService.Coordinate(1,0)),part->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException error){throw new CompletionException(error);}return List.of();});service.tick();assertEquals(2,owners.size());
            var done=pool.submit(()->{try(var ownerSched=mockStatic(Sched.class);var ownerRuntime=mockStatic(RuntimeCompat.class)){ownerSched.when(()->Sched.owns(eq(world),anyInt(),anyInt())).thenReturn(true);ownerRuntime.when(()->RuntimeCompat.texturedSnapshot(chunk)).thenReturn(mock(ChunkSnapshot.class));owners.getFirst().run();}});
            assertTrue(entered.await(3,TimeUnit.SECONDS));request.close();assertEquals(4194304,global.snapshot().live());assertEquals(1,service.metrics().inflight());release.countDown();done.get(3,TimeUnit.SECONDS);assertEquals(0,global.snapshot().live());assertEquals(0,service.metrics().inflight());owners.get(1).run();assertEquals(0,service.metrics().inflight());
        }finally{release.countDown();}
    }
}
