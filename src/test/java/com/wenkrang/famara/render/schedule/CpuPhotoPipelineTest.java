package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.output.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用离线发布端口，不冒充真实 Bukkit MapCanvas 或 CE 服务端验收。
 */
class CpuPhotoPipelineTest {
    @TempDir
    Path directory;

    private AssetRepository repository() throws Exception {
        RenderFixtures f = new RenderFixtures();
        f.png("test:tile", 2, 2, 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff);
        f.cube("test:block/model", "test:tile");
        f.block("test:block", "test:block/model");
        AssetRepository repository = new AssetRepository(2);
        long id = repository.requestBuild();
        assertTrue(repository.publish(id, f.generation(id)));
        return repository;
    }

    private CpuPhotoPipeline.Prepared prepare(AssetRepository repository) {
        var lease = repository.acquire();
        try {
            AssetGeneration g = lease.generation();
            SceneSnapshot.Builder b = new SceneSnapshot.Builder(g, 0, 32, 10, 10000);
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) for (int y = 0; y < 2; y++) b.availableSection(x, y, z);
            b.put(0, 0, 0, RenderFixtures.vanilla("test:block", g.id()), new SceneSnapshot.Lighting(15, 0, 0xffffff), 0);
            var camera = new Camera(new Vec3(.5, .5, -2), Math.PI / 2, 0, Camera.Projection.PERSPECTIVE, Math.PI / 3);
            var settings = new CpuPhotoRenderer.Settings(8, 1, Texture.Filter.NEAREST, false, 0, .5, 20, 0, 0,
                    new VoxelTracer.Limits(10000, 1000, 10000), Map.of());
            var palette = new MapPaletteSnapshot("synthetic", List.of(new MapPaletteSnapshot.Entry(4, 0), new MapPaletteSnapshot.Entry(5, 0xff0000),
                    new MapPaletteSnapshot.Entry(128, 0x00ff00), new MapPaletteSnapshot.Entry(200, 0x0000ff), new MapPaletteSnapshot.Entry(255, 0xffffff)));
            return new CpuPhotoPipeline.Prepared(b.build(), camera, settings, palette, lease);
        } catch (RuntimeException e) {
            lease.close();
            throw e;
        }
    }

    private CpuPhotoPipeline.Limits limits(int jobs) {
        return new CpuPhotoPipeline.Limits(1, jobs, jobs, 64L * 1024 * 1024, 1_000_000, 1_000_000, 10_000_000, 5000, 100, 2048, 10_000_000_000L);
    }

    @Test
    void boundedBackgroundPipelineRendersStoresPublishesAndReleasesAllPermits() throws Exception {
        try (AssetRepository repository = repository()) {
            AtomicInteger calls = new AtomicInteger(), prepared = new AtomicInteger();
            DiskPhotoStore store = new DiskPhotoStore(directory);
            try (CpuPhotoPipeline pipeline = new CpuPhotoPipeline(limits(2), store, (stored, photo, current) -> {
                assertTrue(current.getAsBoolean());
                assertTrue(Thread.currentThread().getName().startsWith("Famara-v2-cpu-"));
                assertEquals(16384, photo.copyRgb().length);
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            })) {
                UUID player = UUID.randomUUID(), request = UUID.randomUUID();
                var handle = pipeline.submit(player, request, 42, 8 * 1024 * 1024, () -> {
                    prepared.incrementAndGet();
                    return prepare(repository);
                });
                var result = handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(PhotoJobState.Stage.SUCCEEDED, handle.state().stage());
                assertEquals(42, result.stored().mapId());
                assertTrue(store.loadCommitted(42).isPresent());
                assertEquals(1, calls.get());
                assertEquals(0, pipeline.admissionSnapshot().admitted());
                assertEquals(0, pipeline.admissionSnapshot().reservedBytes());
                assertTrue(pipeline.cpuSnapshot().settledBatches() > 1);
                assertSame(handle, pipeline.submit(player, request, 42, 8 * 1024 * 1024, () -> {
                    throw new AssertionError("不得重复准备");
                }));
                assertEquals(1, prepared.get());
            }
            assertEquals(1, repository.retainedGenerations());
        }
    }

    @Test
    void preparationFailureNeverInvokesPublisherOrStoresPhoto() throws Exception {
        DiskPhotoStore store = new DiskPhotoStore(directory);
        AtomicInteger published = new AtomicInteger();
        try (CpuPhotoPipeline pipeline = new CpuPhotoPipeline(limits(1), store, (s, p, c) -> {
            published.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        })) {
            var handle = pipeline.submit(UUID.randomUUID(), UUID.randomUUID(), 1, 8 * 1024 * 1024, () -> {
                throw new IllegalArgumentException("injected");
            });
            assertThrows(ExecutionException.class, () -> handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(PhotoJobState.Stage.FAILED, handle.state().stage());
            assertEquals(0, published.get());
            assertTrue(store.committedIds().isEmpty());
            assertEquals(0, pipeline.admissionSnapshot().admitted());
        }
    }

    @Test
    void storedPhotoDoesNotSucceedBeforeAsynchronousPublicationReceipt() throws Exception {
        try (AssetRepository repository = repository()) {
            CompletableFuture<Void> publication = new CompletableFuture<>();
            CountDownLatch called = new CountDownLatch(1);
            try (CpuPhotoPipeline pipeline = new CpuPhotoPipeline(limits(1), new DiskPhotoStore(directory), (s, p, c) -> {
                called.countDown();
                return publication;
            })) {
                var handle = pipeline.submit(UUID.randomUUID(), UUID.randomUUID(), 3, 8 * 1024 * 1024, () -> prepare(repository));
                assertTrue(called.await(10, TimeUnit.SECONDS));
                assertTrue(handle.state().stored());
                assertEquals(PhotoJobState.Stage.COMMITTING, handle.state().stage());
                assertFalse(handle.result().toCompletableFuture().isDone());
                assertEquals(PhotoJobState.CancelResult.TOO_LATE, handle.cancel());
                publication.complete(null);
                handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(PhotoJobState.Stage.SUCCEEDED, handle.state().stage());
            }
        }
    }

    @Test
    void admissionRejectsBeforeCallingAnotherSceneFactory() throws Exception {
        try (AssetRepository repository = repository()) {
            CompletableFuture<Void> publication = new CompletableFuture<>();
            CountDownLatch called = new CountDownLatch(1);
            try (CpuPhotoPipeline pipeline = new CpuPhotoPipeline(limits(1), new DiskPhotoStore(directory), (s, p, c) -> {
                called.countDown();
                return publication;
            })) {
                var one = pipeline.submit(UUID.randomUUID(), UUID.randomUUID(), 4, 8 * 1024 * 1024, () -> prepare(repository));
                assertTrue(called.await(10, TimeUnit.SECONDS));
                AtomicInteger prepared = new AtomicInteger();
                assertThrows(RejectedExecutionException.class, () -> pipeline.submit(UUID.randomUUID(), UUID.randomUUID(), 5, 8 * 1024 * 1024,
                        () -> {
                            prepared.incrementAndGet();
                            return prepare(repository);
                        }));
                assertEquals(0, prepared.get());
                publication.complete(null);
                one.result().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void shutdownInvalidatesLatePublisherCallbackAndKeepsTaskFailed() throws Exception {
        try (AssetRepository repository = repository()) {
            CompletableFuture<Void> publication = new CompletableFuture<>();
            CountDownLatch called = new CountDownLatch(1);
            AtomicReference<java.util.function.BooleanSupplier> valid = new AtomicReference<>();
            CpuPhotoPipeline pipeline = new CpuPhotoPipeline(limits(1), new DiskPhotoStore(directory), (s, p, c) -> {
                valid.set(c);
                called.countDown();
                return publication;
            });
            try {
                var handle = pipeline.submit(UUID.randomUUID(), UUID.randomUUID(), 6, 8 * 1024 * 1024, () -> prepare(repository));
                assertTrue(called.await(10, TimeUnit.SECONDS));
                pipeline.close();
                assertFalse(valid.get().getAsBoolean());
                assertThrows(ExecutionException.class, () -> handle.result().toCompletableFuture().get(10, TimeUnit.SECONDS));
                publication.complete(null);
                assertEquals(PhotoJobState.Stage.FAILED, handle.state().stage());
                assertEquals(0, pipeline.admissionSnapshot().admitted());
            } finally {
                pipeline.close();
            }
        }
    }

    @Test void waitingPreparationDoesNotPollAndDeferredMapIsBoundOnlyOnce() throws Exception {
        try(AssetRepository resources=repository()) {
            CountDownLatch waiting=new CountDownLatch(1),rendered=new CountDownLatch(1);
            AtomicReference<Runnable> wake=new AtomicReference<>();AtomicInteger steps=new AtomicInteger(),closes=new AtomicInteger();
            AtomicBoolean ready=new AtomicBoolean();
            var preparation=new CpuPhotoPipeline.Preparation() {
                CpuPhotoPipeline.Prepared value;
                public void wakeup(Runnable callback){wake.set(callback);}
                public CpuPhotoPipeline.PreparationStep step(int operations,long deadline) {
                    steps.incrementAndGet();
                    if(!ready.get()){waiting.countDown();return CpuPhotoPipeline.PreparationStep.WAITING;}
                    value=prepare(resources);return CpuPhotoPipeline.PreparationStep.DONE;
                }
                public CpuPhotoPipeline.Prepared result(){return value;}
                public void close(){closes.incrementAndGet();}
            };
            try(var pipeline=new CpuPhotoPipeline(limits(2),new DiskPhotoStore(directory),(s,p,c)->{
                rendered.countDown();return CompletableFuture.completedFuture(null);
            })) {
                UUID player=UUID.randomUUID(),id=UUID.randomUUID();
                var first=pipeline.submitDeferred(player,id,8*1024*1024,preparation);
                assertTrue(waiting.await(10,TimeUnit.SECONDS));assertEquals(-1,first.mapId());
                // 另一任务推进多个批次，证明WAITING任务没有由调度器持续轮询。
                pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),90,8*1024*1024,()->prepare(resources)).result().toCompletableFuture().get(10,TimeUnit.SECONDS);
                assertEquals(1,steps.get());ready.set(true);
                for(int n=0;n<50;n++)wake.get().run();
                assertTrue(first.bindOutput(91));assertTrue(first.bindOutput(91));
                assertThrows(IllegalStateException.class,()->first.bindOutput(92));
                first.result().toCompletableFuture().get(10,TimeUnit.SECONDS);
                assertEquals(2,steps.get());assertEquals(1,closes.get());
                assertSame(first,pipeline.submitDeferred(player,id,8*1024*1024,preparation));
                assertEquals(0,pipeline.admissionSnapshot().admitted());
            }
        }
    }
    @Test void cancellationClosesWaitingPreparationAndIgnoresLateWakeups() throws Exception {
        CountDownLatch waiting=new CountDownLatch(1);AtomicReference<Runnable> wake=new AtomicReference<>();
        AtomicInteger calls=new AtomicInteger(),closes=new AtomicInteger();
        var prep=new CpuPhotoPipeline.Preparation() {
            public void wakeup(Runnable callback){wake.set(callback);}
            public CpuPhotoPipeline.PreparationStep step(int operations,long deadline){calls.incrementAndGet();waiting.countDown();return CpuPhotoPipeline.PreparationStep.WAITING;}
            public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}
            public void close(){closes.incrementAndGet();}
        };
        try(var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            var handle=pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),8*1024*1024,prep);
            assertTrue(waiting.await(10,TimeUnit.SECONDS));handle.cancel();
            assertInstanceOf(CancellationException.class,assertThrows(CompletionException.class,()->handle.result().toCompletableFuture().join()).getCause());
            for(int n=0;n<100;n++)wake.get().run();
            assertFalse(handle.bindOutput(9));assertEquals(1,calls.get());assertEquals(1,closes.get());
            assertEquals(0,pipeline.admissionSnapshot().reservedBytes());
        }
    }
    @Test void failingWakeupRegistrationAndCloseCannotLeakAdmission() throws Exception {
        var prep=new CpuPhotoPipeline.Preparation() {
            public void wakeup(Runnable callback){throw new IllegalStateException("registration");}
            public CpuPhotoPipeline.PreparationStep step(int operations,long deadline){throw new AssertionError();}
            public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}
            public void close(){throw new IllegalStateException("cleanup");}
        };
        try(var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            var handle=pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),8*1024*1024,prep);
            assertThrows(CompletionException.class,()->handle.result().toCompletableFuture().join());
            assertEquals(0,pipeline.admissionSnapshot().admitted());
        }
    }

    @Test void insufficientPreparationMemoryRejectsBeforeWakeupOrAllocation() throws Exception {
        AtomicInteger closes=new AtomicInteger();
        var preparation=new CpuPhotoPipeline.Preparation() {
            public long minimumMemoryBytes(){return 16*1024*1024;}
            public void wakeup(Runnable callback){throw new AssertionError("不能开始采集");}
            public CpuPhotoPipeline.PreparationStep step(int operations,long deadline){throw new AssertionError();}
            public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}
            public void close(){closes.incrementAndGet();}
        };
        try(var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            assertThrows(RejectedExecutionException.class,()->pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),8*1024*1024,preparation));
            assertEquals(0,pipeline.admissionSnapshot().admitted());assertEquals(0,closes.get());
            // 准入失败时所有权没有转移，调用方负责关闭自己创建的准备器。
            preparation.close();assertEquals(1,closes.get());
        }
    }

    @Test void finalSettlementWaitsForDurableReceiptEvenWhenPluginCloses() throws Exception {
        try(var assets=repository()) {
            CompletableFuture<Void> durable=new CompletableFuture<>();CountDownLatch committing=new CountDownLatch(1);
            var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(stored,photo,current)->{
                var gate=assertInstanceOf(CpuPhotoPipeline.CommitGate.class,current);assertTrue(gate.beginSettlement());committing.countDown();return durable;
            });
            try {
                var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),77,8*1024*1024,()->prepare(assets));
                assertTrue(committing.await(10,TimeUnit.SECONDS));pipeline.close();
                assertFalse(handle.result().toCompletableFuture().isDone());assertEquals(PhotoJobState.Stage.COMMITTING,handle.state().stage());
                durable.complete(null);handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS);
                assertEquals(PhotoJobState.Stage.SUCCEEDED,handle.state().stage());assertEquals(0,pipeline.admissionSnapshot().admitted());
            } finally{durable.completeExceptionally(new IllegalStateException("cleanup"));pipeline.close();}
        }
    }
    @Test void durableSettlementFailureNeverReportsSuccessOrLeaksReservation() throws Exception {
        try(var assets=repository();var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(stored,photo,current)->{
            assertTrue(((CpuPhotoPipeline.CommitGate)current).beginSettlement());return CompletableFuture.failedFuture(new java.io.IOException("ledger full"));
        })) {
            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),78,8*1024*1024,()->prepare(assets));
            assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(PhotoJobState.Stage.FAILED,handle.state().stage());assertEquals(0,pipeline.admissionSnapshot().admitted());
        }
    }













    @Test void timeoutIncludesPublishedOwnerProgressAndReturnsAllPermits()throws Exception {
        AtomicInteger closes=new AtomicInteger();AtomicReference<Thread> owner=new AtomicReference<>();
        var prep=new CpuPhotoPipeline.Preparation() {
            public CpuPhotoPipeline.PreparationStep step(int operations,long deadline){owner.set(Thread.currentThread());return CpuPhotoPipeline.PreparationStep.WAITING;}
            public CpuPhotoPipeline.PreparationProgress progress(){assertSame(owner.get(),Thread.currentThread());return new CpuPhotoPipeline.PreparationProgress("等待区块",3,90,"LOADED：等待owner预算");}
            public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}
            public void close(){closes.incrementAndGet();}
        };
        var policy=new CpuPhotoPipeline.Limits(1,1,1,64L*1024*1024,1000000,1000000,8000000,1000,50,1024,500000000);
        try(var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            UUID id=UUID.randomUUID();var handle=pipeline.submitDeferred(UUID.randomUUID(),id,8*1024*1024,prep);
            var error=assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class,error.getCause());String message=error.getCause().getMessage();
            assertTrue(message.contains("等待区块 3/90"));assertTrue(message.contains(id.toString()));assertTrue(message.contains("cpuMs="));assertTrue(message.contains("wallMs="));
            assertEquals("等待区块",handle.preparationProgress().phase());assertEquals(1,closes.get());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());
        }
    }


    @Test void expiredRunningOwnerCannotReplaceTimeoutEvidenceWithALateFailure()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger steps=new AtomicInteger(),closes=new AtomicInteger();
        var prep=new CpuPhotoPipeline.Preparation() {
            public CpuPhotoPipeline.PreparationStep step(int operations,long deadline)throws Exception {
                if(steps.incrementAndGet()==1)return CpuPhotoPipeline.PreparationStep.CONTINUE;
                entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("测试owner未释放");throw new IllegalStateException("迟到的owner异常");
            }
            public CpuPhotoPipeline.PreparationProgress progress(){return new CpuPhotoPipeline.PreparationProgress("转换section",7,20,"");}
            public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}
            public void close(){closes.incrementAndGet();}
        };
        var policy=new CpuPhotoPipeline.Limits(1,1,1,64L*1024*1024,1000000,1000000,8000000,1000,50,1024,500000000);
        try(var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            var handle=pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),8*1024*1024,prep);
            assertTrue(entered.await(2,TimeUnit.SECONDS));CompletableFuture.delayedExecutor(750,TimeUnit.MILLISECONDS).execute(release::countDown);
            var error=assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class,error.getCause());assertTrue(error.getCause().getMessage().contains("转换section 7/20"));
            assertEquals(1,closes.get());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());
        }finally{release.countDown();}
    }

    @Test void onePhotoReallyRendersOnTwoOwnersAndPublishesOnlyOnce()throws Exception {
        CountDownLatch both=new CountDownLatch(2);Set<Thread> threads=ConcurrentHashMap.newKeySet();Set<Integer> seen=ConcurrentHashMap.newKeySet();AtomicInteger publishes=new AtomicInteger();
        var policy=new CpuPhotoPipeline.Limits(2,1,1,64L*1024*1024,1000000,1000000,8000000,2000,100,256,10000000000L);
        try(var resources=repository();var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(stored,photo,current)->{
            assertEquals("2",photo.metadata().get("renderLaneCount"));assertEquals("2",photo.metadata().get("peakRenderLanes"));publishes.incrementAndGet();return CompletableFuture.completedFuture(null);
        },new CpuBudget(2000,100,System::nanoTime),(request,lane)->{
            if(seen.add(lane)){threads.add(Thread.currentThread());both.countDown();try{if(!both.await(3,TimeUnit.SECONDS))throw new AssertionError("同照片的两路未同时进入真实renderBatch");}catch(InterruptedException error){throw new CompletionException(error);}}
        })) {
            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),120,32L*1024*1024,()->prepare(resources));handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertEquals(2,threads.size());assertEquals(2,handle.peakRenderLanes());assertEquals(1,publishes.get());assertTrue(handle.renderWallNanos()>0);assertEquals(0,pipeline.admissionSnapshot().reservedBytes());assertEquals(0,pipeline.cpuSnapshot().reservedNanos());
        }
    }
    @Test void failedLaneWaitsForTheOtherOwnerBeforeReturningMemoryOrPartialPixels()throws Exception {
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),failed=new CountDownLatch(1);AtomicBoolean once=new AtomicBoolean();
        var policy=new CpuPhotoPipeline.Limits(2,1,1,64L*1024*1024,1000000,1000000,8000000,2000,100,256,10000000000L);
        try(var resources=repository();var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError("部分图不能发布");},new CpuBudget(2000,100,System::nanoTime),(request,lane)->{
            try {
                if(lane==0 && once.compareAndSet(false,true)){blocked.countDown();if(!release.await(4,TimeUnit.SECONDS))throw new AssertionError("测试owner未释放");}
                else if(lane==1){if(!blocked.await(3,TimeUnit.SECONDS))throw new AssertionError();failed.countDown();throw new IllegalArgumentException("第二路首因");}
            }catch(InterruptedException error){throw new CompletionException(error);}
        })) {
            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),121,32L*1024*1024,()->prepare(resources));
            assertTrue(failed.await(4,TimeUnit.SECONDS));assertFalse(handle.result().toCompletableFuture().isDone());assertEquals(1,pipeline.admissionSnapshot().admitted());
            release.countDown();var error=assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS));assertInstanceOf(IllegalArgumentException.class,error.getCause());assertEquals("第二路首因",error.getCause().getMessage());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());assertEquals(0,pipeline.cpuSnapshot().reservedNanos());
        }finally{release.countDown();}
    }
    @Test void cancellingBothRenderOwnersDoesNotReleaseTheirWorkspaceEarly()throws Exception {
        CountDownLatch both=new CountDownLatch(2),release=new CountDownLatch(1);Set<Integer> entered=ConcurrentHashMap.newKeySet();var global=new RenderMemoryBudget(536870912);
        var policy=new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,100,256,10000000000L);
        try(var resources=repository();var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();},new CpuBudget(2000,100,System::nanoTime),(request,lane)->{
            if(entered.add(lane)){both.countDown();try{if(!release.await(4,TimeUnit.SECONDS))throw new AssertionError();}catch(InterruptedException error){throw new CompletionException(error);}}
        })) {
            var scope=new MemoryScope(global,"render-test",201326592);
            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),122,201326592,()->{var p=prepare(resources);return new CpuPhotoPipeline.Prepared(p.scene(),p.camera(),p.settings(),p.palette(),p.lease(),p.sourceMetadata(),scope);});
            assertTrue(both.await(4,TimeUnit.SECONDS));assertEquals(PhotoJobState.CancelResult.CANCELLED,handle.cancel());assertFalse(handle.result().toCompletableFuture().isDone());assertEquals(201326592,global.snapshot().live());
            release.countDown();assertInstanceOf(CancellationException.class,assertThrows(CompletionException.class,()->handle.result().toCompletableFuture().join()).getCause());assertEquals(0,global.snapshot().live());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());
        }finally{release.countDown();}
    }

    @Test void secondLaneAllocationFailureCannotLaunchHalfAGroupOrLeakTheWorkspace()throws Exception {
        var global=new RenderMemoryBudget(536870912);AtomicInteger created=new AtomicInteger(),entered=new AtomicInteger();
        var policy=new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,100,256,10000000000L);
        try(var resources=repository();var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();},new CpuBudget(2000,100,System::nanoTime),(id,lane)->entered.incrementAndGet(),(scene,camera,settings)->{
            if(created.incrementAndGet()==2)throw new OutOfMemoryError("注入第二路构造失败");return new CpuPhotoRenderer(scene,camera,settings);
        })) {
            var scope=new MemoryScope(global,"allocation-test",201326592);
            var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),123,201326592,()->{var p=prepare(resources);return new CpuPhotoPipeline.Prepared(p.scene(),p.camera(),p.settings(),p.palette(),p.lease(),Map.of(),scope);});
            var error=assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS));assertInstanceOf(OutOfMemoryError.class,error.getCause());assertEquals(2,created.get());assertEquals(0,entered.get());assertEquals(0,global.snapshot().live());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());assertEquals(0,pipeline.cpuSnapshot().reservedNanos());
        }
    }
    @Test void rejectedExecutorSubmissionDiscardsTheOwnerAndItsCpuReservation()throws Exception {
        AtomicInteger closes=new AtomicInteger();
        try(var pipeline=new CpuPhotoPipeline(limits(1),new DiskPhotoStore(directory),(s,p,c)->{throw new AssertionError();})) {
            var field=CpuPhotoPipeline.class.getDeclaredField("workers");field.setAccessible(true);((ThreadPoolExecutor)field.get(pipeline)).shutdown();
            var preparation=new CpuPhotoPipeline.Preparation(){public CpuPhotoPipeline.PreparationStep step(int n,long deadline){throw new AssertionError();}public CpuPhotoPipeline.Prepared result(){throw new AssertionError();}public void close(){closes.incrementAndGet();}};
            var handle=pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),8L*1024*1024,preparation);
            assertInstanceOf(RejectedExecutionException.class,assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS)).getCause());assertEquals(1,closes.get());assertEquals(0,pipeline.admissionSnapshot().reservedBytes());assertEquals(0,pipeline.cpuSnapshot().reservedNanos());
        }
    }

    @Test void legacyBatchConstructorAndExplicitScanQuantumKeepTheirOwnBounds() {
        var legacy=limits(1);assertEquals(legacy.maxBatchCpuNanos(),legacy.scanBatchCpuNanos());
        var policy=new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,100,1024,120000000000L,10000000L);assertEquals(8000000,policy.maxBatchCpuNanos());assertEquals(10000000,policy.scanBatchCpuNanos());
        assertThrows(IllegalArgumentException.class,()->new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,100,1024,120000000000L,101000000L));
        assertThrows(IllegalArgumentException.class,()->new CpuPhotoPipeline.Limits(2,1,1,536870912,1000000,1000000,8000000,2000,10,1024,120000000000L,11000000L));
    }

    @Test void switchingOutOfScanDoesNotCarryItsLongerDeadlineIntoOtherPreparationWork()throws Exception {
        AtomicBoolean scanning=new AtomicBoolean(true);AtomicLong scanDeadline=new AtomicLong();AtomicInteger steps=new AtomicInteger();
        try(var resources=repository()) {
            var prep=new CpuPhotoPipeline.Preparation() {
                CpuPhotoPipeline.Prepared ready;
                public boolean scanning(){return scanning.get();}
                public CpuPhotoPipeline.PreparationStep step(int operations,long deadline) {
                    if(steps.getAndIncrement()==0){scanDeadline.set(deadline);scanning.set(false);return CpuPhotoPipeline.PreparationStep.CONTINUE;}
                    assertNotEquals(scanDeadline.get(),deadline,"阶段转换必须让出，不能把10ms期限用于索引");ready=prepare(resources);return CpuPhotoPipeline.PreparationStep.DONE;
                }
                public CpuPhotoPipeline.Prepared result(){return ready;}
            };
            var policy=new CpuPhotoPipeline.Limits(1,1,1,64L*1024*1024,1000000,1000000,8000000,2000,100,1024,10000000000L,10000000L);
            try(var pipeline=new CpuPhotoPipeline(policy,new DiskPhotoStore(directory),(s,p,c)->CompletableFuture.completedFuture(null))){var handle=pipeline.submitDeferred(UUID.randomUUID(),UUID.randomUUID(),32L*1024*1024,prep);assertTrue(handle.bindOutput(124));handle.result().toCompletableFuture().get(10,TimeUnit.SECONDS);assertEquals(2,steps.get());}
        }
    }
}
