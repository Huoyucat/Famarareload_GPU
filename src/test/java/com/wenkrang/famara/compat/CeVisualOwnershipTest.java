package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.platform.*;
import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CeVisualOwnershipTest {
    @AfterEach void reset(){CeCompat.backendForTests(null);}
    private static final class Work {
        final Supplier<?> action;final CompletableFuture<Object> result=new CompletableFuture<>();
        Work(Supplier<?> action){this.action=action;}
        void run(){try{result.complete(action.get());}catch(Throwable e){result.completeExceptionally(e);}}
    }
    private static final class Queue {
        final BudgetedWorkRunner runner=mock(BudgetedWorkRunner.class);final ArrayDeque<Work> work=new ArrayDeque<>();
        Queue(){when(runner.submit(any())).thenAnswer(call->{var next=new Work(call.getArgument(0));work.addLast(next);return next.result.minimalCompletionStage();});}
        Work next(){return work.removeFirst();}
        void drain(){while(!work.isEmpty())next().run();}
    }
    private void queued(CeVisualResourceService service,Queue queue,int expected) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(queue.work.size()<expected) {
            assertFalse(service.readiness().toCompletableFuture().isCompletedExceptionally(),service::status);
            assertTrue(System.nanoTime()<deadline,service::status);service.tick();Thread.yield();
        }
        assertEquals(expected,queue.work.size());
    }
    private void backend(int rows) {
        CeCompat.backendForTests(new CeCompat.Backend(){public boolean enabled(){return true;}public CeCompat.VisualCursor visualCursor(){return new CeCompat.VisualCursor(){int n;
            public boolean done(){return n==rows;}public List<CeCompat.VisualState> next(int maximum){return List.of(new CeCompat.VisualState("craftengine:custom_"+n++,"minecraft:stone","test:id",null,"test:block"));}
        };}});
    }
    private BudgetedAssetLoader loader(CompletableFuture<BudgetedAssetLoader.Staged> future) {
        var loader=mock(BudgetedAssetLoader.class);when(loader.prepareSources(anyList(),any())).thenReturn(future);return loader;
    }
    private CeVisualResourceService service(RenderMemoryBudget memory,AssetRepository repository,BudgetedAssetLoader loader,Queue queue,int perTick) {
        return new CeVisualResourceService(Path.of("not-read"),RenderFixtures.LIMITS,new CeVisualResourceService.Limits(100,1048576,perTick,50000000,1000000),repository,loader,queue.runner,new CpuBudget(5000,100,System::nanoTime),e->{}).memory(memory);
    }
    private BudgetedAssetLoader.Staged candidate(AssetRepository repository,RenderMemoryBudget memory)throws Exception {
        var f=new RenderFixtures();f.png("test:tile",1,1,0xffffffff);f.cube("test:model","test:tile");f.block("test:block","test:model");long ticket=repository.requestBuild();var assets=f.generation(ticket);assets.memory(memory.reserve("candidate-assets",1048576));
        return new BudgetedAssetLoader.Staged(new BudgetedAssetLoader.Result(ticket,1,1,1,assets.estimatedRetainedBytes()),assets);
    }
    @Test void queuedOwnerKeepsMemoryUntilItsCancellationCallbackAndLateAssetsAreReleased()throws Exception {
        backend(1);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,10)) {
            queued(service,queue,1);long live=memory.snapshot().live();assertTrue(live>0);service.close();assertEquals(live,memory.snapshot().live());
            queue.drain();assertEquals(0,memory.snapshot().live());disk.complete(candidate(repository,memory));assertEquals(0,memory.snapshot().live());assertTrue(service.ready().isEmpty());assertThrows(IllegalStateException.class,repository::acquire);
        }
    }
    @Test void activeOwnerCannotLoseItsReservationWhenServiceCloses()throws Exception {
        backend(1);var actual=new RenderMemoryBudget(536870912);var memory=spy(actual);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{var lease=spy(actual.reserve(call.getArgument(0),call.getArgument(1)));
            doAnswer(resize->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));resize.callRealMethod();return null;}).when(lease).resize(anyLong());return lease;
        }).when(memory).reserve(anyString(),anyLong());
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,10);var thread=Executors.newSingleThreadExecutor()) {
            // 首次resize是冻结记录；先不阻塞Global，转换owner开始时再装闩锁。
            release.countDown();queued(service,queue,1);
            var held=new CountDownLatch(1);var proceed=new CountDownLatch(1);
            var field=CeVisualResourceService.class.getDeclaredField("building");field.setAccessible(true);Object build=field.get(service);var memoryField=build.getClass().getDeclaredField("memory");memoryField.setAccessible(true);var lease=(RenderMemoryBudget.Lease)memoryField.get(build);
            doAnswer(resize->{held.countDown();assertTrue(proceed.await(5,TimeUnit.SECONDS));resize.callRealMethod();return null;}).when(lease).resize(anyLong());
            var task=thread.submit(queue.next()::run);assertTrue(held.await(5,TimeUnit.SECONDS));long live=actual.snapshot().live();service.close();assertEquals(live,actual.snapshot().live());proceed.countDown();task.get(5,TimeUnit.SECONDS);assertEquals(0,actual.snapshot().live());assertTrue(service.ready().isEmpty());
        } finally {release.countDown();}
    }
    @Test void publishedInputRetainsOldTableAndAssetsUntilPhotoExits()throws Exception {
        backend(1);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,10)) {
            queued(service,queue,1);disk.complete(candidate(repository,memory));queue.drain();service.readiness().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertTrue(memory.snapshot().domains().keySet().stream().anyMatch(s->s.startsWith("visual-table-")));assertTrue(memory.snapshot().live()<2*1048576);
            var photo=service.acquire();service.close();repository.close();assertTrue(memory.snapshot().live()>0);photo.close();photo.close();assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void perSliceLimitYieldsAndCancelledQueuedContinuationCleansUpOnce() {
        backend(3);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,1)) {
            service.tick();service.tick();queued(service,queue,1);queue.next().run();assertEquals(1,queue.work.size());assertFalse(service.readiness().toCompletableFuture().isDone());
            service.close();assertTrue(memory.snapshot().live()>0);queue.next().result.completeExceptionally(new CancellationException("后台关闭"));assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void rejectedContinuationFailsWithoutPublishingHalfATable() {
        backend(2);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,1)) {
            queued(service,queue,1);when(queue.runner.submit(any())).thenThrow(new RejectedExecutionException("queue full"));queue.next().run();assertEquals(0,memory.snapshot().live());assertTrue(service.status().contains("queue full"));assertTrue(service.ready().isEmpty());
        }
    }
    @Test void loaderFailureDoesNotReleaseAnOutstandingConversionOwner() {
        backend(1);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,10)) {
            queued(service,queue,1);long held=memory.snapshot().live();disk.completeExceptionally(new IllegalStateException("asset failed"));assertEquals(held,memory.snapshot().live());assertTrue(service.status().contains("asset failed"));queue.drain();assertEquals(0,memory.snapshot().live());
        }
    }
    @Test void epochSwitchCannotPublishOldTableOrFreeTheNewBuildsLease()throws Exception {
        backend(1);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var first=new CompletableFuture<BudgetedAssetLoader.Staged>();var second=new CompletableFuture<BudgetedAssetLoader.Staged>();var loader=mock(BudgetedAssetLoader.class);
        when(loader.prepareSources(anyList(),any())).thenReturn(first.minimalCompletionStage(),second.minimalCompletionStage());
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader,queue,10)) {
            queued(service,queue,1);var old=service.readiness();CeCompat.invalidateVisualResources();service.tick();queued(service,queue,2);assertTrue(old.toCompletableFuture().isCompletedExceptionally());
            long two=memory.snapshot().live();queue.next().run();assertTrue(memory.snapshot().live()<two);assertTrue(memory.snapshot().live()>0);first.complete(candidate(repository,memory));assertTrue(service.ready().isEmpty());
            second.complete(candidate(repository,memory));queue.drain();var ready=service.readiness().toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals(CeCompat.visualEpoch(),ready.epoch());assertEquals(1,ready.table().size());
        }
        assertEquals(0,memory.snapshot().live());
    }
    @Test void alreadyFailedAssetFutureStopsGlobalFreezeImmediately() {
        backend(1);var memory=new RenderMemoryBudget(536870912);var queue=new Queue();var disk=new CompletableFuture<BudgetedAssetLoader.Staged>();disk.completeExceptionally(new IllegalStateException("synchronous failure"));
        try(var repository=new AssetRepository(2);var service=service(memory,repository,loader(disk),queue,10)) {
            service.tick();assertTrue(service.status().contains("synchronous failure"));assertTrue(queue.work.isEmpty());assertEquals(0,memory.snapshot().live());
        }
    }

}
