package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.gpu.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.math.Vec3;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.output.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real store and film ledger, fake device: exercises the original pipeline transaction boundary. */
class GpuPhotoPipelineTest {
    @TempDir Path directory;
    static GpuConfiguration config(boolean fallback){return new GpuConfiguration(GpuConfiguration.Mode.OPENCL,"Quadro P620",384,1,64,128,1000,fallback,1.2);}
    static CpuPhotoPipeline.Limits limits(long timeout){return new CpuPhotoPipeline.Limits(1,2,2,64L*1024*1024,1_000_000,1_000_000,8_000_000,5000,100,2048,timeout);}
    static <T> T checked(Callable<T> action){try{return action.call();}catch(Exception error){throw new CompletionException(error);}}
    static final class Inputs implements AutoCloseable {
        final AssetRepository assets=new AssetRepository(2);final RenderMemoryBudget host=new RenderMemoryBudget(536870912);final AtomicInteger captures=new AtomicInteger();AssetRepository.Lease last;
        Inputs()throws Exception {var f=new RenderFixtures();f.png("test:white",1,1,0xffffffff);f.cube("test:cube","test:white");f.block("test:block","test:cube");long id=assets.requestBuild();assertTrue(assets.publish(id,f.generation(id)));}
        CpuPhotoPipeline.Prepared prepare() {
            captures.incrementAndGet();last=assets.acquire();var scope=new MemoryScope(host,"gpu-pipeline-test",32L*1024*1024);
            try {
                var b=new SceneSnapshot.Builder(last.generation(),0,32,10,10000).memory(scope);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=0;y<2;y++)b.availableSection(x,y,z);
                b.put(0,0,0,RenderFixtures.vanilla("test:block",last.generation().id()),new SceneSnapshot.Lighting(15,0,0xffffff),0);
                return new CpuPhotoPipeline.Prepared(b.build(),new Camera(new Vec3(.5,.5,-2),Math.PI/2,0,Camera.Projection.LEGACY_ANGULAR,1),new CpuPhotoRenderer.Settings(8,1,Texture.Filter.NEAREST,false,0,.5,32,0,0,new VoxelTracer.Limits(10000,1000,10000),Map.of()),new MapPaletteSnapshot("fixture",List.of(new MapPaletteSnapshot.Entry(4,0),new MapPaletteSnapshot.Entry(5,0xffffff))),last,Map.of(),scope);
            }catch(Throwable error){last.close();scope.close();throw error;}
        }
        void released(){assertEquals(0,host.snapshot().live());assertThrows(IllegalStateException.class,last::generation);}
        public void close(){assets.close();}
    }
    static final class Device implements PhotoRenderBackend {
        final AtomicInteger calls=new AtomicInteger(),cancels=new AtomicInteger();final CountDownLatch entered=new CountDownLatch(1);final CompletableFuture<Result> delayed=new CompletableFuture<>();
        final Throwable failure;final boolean wait,rejectSecond;
        Device(Throwable failure,boolean wait,boolean rejectSecond){this.failure=failure;this.wait=wait;this.rejectSecond=rejectSecond;}
        public Capabilities capabilities(){return new Capabilities("OPENCL",true,Set.of("FIXTURE"),"");}
        public String deviceName(){return "Quadro P620";}
        public int activeJobs(){return wait && calls.get()>0 && !delayed.isDone()?1:0;}
        public Prepared prepare(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings){return new Prepared(UUID.randomUUID(),scene,camera,settings);}
        static Result frame(){int[] rgb=new int[16384];Arrays.fill(rgb,0xffffff);return new Result(rgb,Map.of("backend","OPENCL","renderCpuNanos","17"));}
        public CompletionStage<Result> submitAsync(Prepared prepared,BooleanSupplier cancelled){int n=calls.incrementAndGet();entered.countDown();if(rejectSecond && n>1)return CompletableFuture.failedFuture(new RejectedExecutionException("device busy"));if(failure!=null)return CompletableFuture.failedFuture(failure);return wait?delayed:CompletableFuture.completedFuture(frame());}
        public Map<String,String> diagnostics(UUID id){return Map.of("renderCpuNanos","17","gpuDevice","fake P620","serializeMs","1","uploadMs","2","kernelMs","3","readbackMs","4","vramPeak","5");}
        public void cancel(UUID id){cancels.incrementAndGet();}
        public void close(){delayed.completeExceptionally(new CancellationException("fake device closed"));}
    }
    static CpuPhotoPipeline.Publisher publisher(CameraLedger ledger,BudgetedWorkRunner io,AtomicInteger calls,boolean fail) {
        return (stored,photo,current)->{
            calls.incrementAndGet();assertTrue(current.getAsBoolean());assertTrue(((CpuPhotoPipeline.CommitGate)current).beginSettlement());
            if(fail)return CompletableFuture.failedFuture(new java.io.IOException("injected publication failure"));
            UUID request=UUID.fromString(photo.metadata().get("request"));return io.submit(()->checked(()->{ledger.succeed(request);return (Void)null;}));
        };
    }
    @Test void deviceFailuresRetryCpuOnceReuseCaptureAndChargeExactlyOneFilm()throws Exception {
        for(var code:List.of(BackendException.Code.UNAVAILABLE,BackendException.Code.UNSUPPORTED,BackendException.Code.OUT_OF_MEMORY,BackendException.Code.TIMEOUT,BackendException.Code.DEVICE_ERROR,BackendException.Code.CLOSED)) {
            Path root=directory.resolve(code.name());var cpu=new CpuBudget(5000,100,System::nanoTime);var publishes=new AtomicInteger();UUID request=UUID.randomUUID(),owner=UUID.randomUUID(),camera=UUID.randomUUID();
            try(var inputs=new Inputs();var book=new CameraLedger(root.resolve("ledger"),new CameraLedger.Limits(10,10,1048576));var io=new BudgetedWorkRunner(4,1_000_000,1_000_000,cpu);var gpu=new Device(new BackendException(code,"injected GPU failure"),false,false)) {
                io.submit(()->checked(()->{book.reserve(request,camera,owner,2,0);book.bindOutput(request,7);return null;})).toCompletableFuture().get(2,TimeUnit.SECONDS);
                var store=new DiskPhotoStore(root.resolve("photos"));
                try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,publisher(book,io,publishes,false),cpu,config(true),gpu)) {
                    var handle=pipeline.submit(owner,request,7,32L*1024*1024,inputs::prepare);handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS);
                    var photo=store.loadCommitted(7).orElseThrow();assertEquals("CPU",photo.metadata().get("backend"));assertEquals("1",photo.metadata().get("gpuFallbackCount"));assertTrue(handle.diagnostics().contains("backend=CPU"));assertTrue(handle.diagnostics().contains("renderCpuNanos="));assertTrue(photo.metadata().get("fallbackReason").startsWith(code.name()));assertEquals(1,gpu.calls.get());assertEquals(1,inputs.captures.get());assertEquals(1,publishes.get());assertEquals(1,book.snapshot().spent(camera));assertEquals(CameraLedger.Status.SUCCEEDED,book.snapshot().job(request).orElseThrow().status());assertEquals(List.of(7),store.committedIds());assertEquals(0,pipeline.admissionSnapshot().admitted());inputs.released();
                    assertSame(handle,pipeline.submit(owner,request,7,32L*1024*1024,()->{throw new AssertionError("Duplicate request captured again");}));assertEquals(1,book.snapshot().spent(camera));
                }
            }
        }
    }
    @Test void disabledFallbackFailsBeforeOutputAndReleasesReservedFilm()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);UUID request=UUID.randomUUID(),owner=UUID.randomUUID(),camera=UUID.randomUUID();
        try(var inputs=new Inputs();var book=new CameraLedger(directory.resolve("ledger"),new CameraLedger.Limits(10,10,1048576));var gpu=new Device(new BackendException(BackendException.Code.DEVICE_ERROR,"injected"),false,false)) {
            book.reserve(request,camera,owner,2,0);book.bindOutput(request,1);var store=new DiskPhotoStore(directory.resolve("photos"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->{throw new AssertionError("Must not publish");},cpu,config(false),gpu)) {
                var h=pipeline.submit(owner,request,1,32L*1024*1024,inputs::prepare);assertThrows(ExecutionException.class,()->h.result().toCompletableFuture().get(5,TimeUnit.SECONDS));book.fail(request,"GPU unavailable and CPU fallback disabled");
                assertEquals(1,gpu.calls.get());assertTrue(store.committedIds().isEmpty());assertEquals(0,book.snapshot().spent(camera));assertEquals(0,book.snapshot().balance(camera,2,0).pending());inputs.released();
            }
        }
    }
    @Test void cancellationPinsSourceUntilLateGpuCompletionAndNeverPublishes()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);
        try(var inputs=new Inputs();var gpu=new Device(null,true,false)) {
            var store=new DiskPhotoStore(directory.resolve("photos"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->{throw new AssertionError("Cancelled photo published");},cpu,config(true),gpu)) {
                var h=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));assertEquals(PhotoJobState.CancelResult.CANCELLED,h.cancel());assertFalse(h.result().toCompletableFuture().isDone());assertTrue(inputs.host.snapshot().live()>0);assertTrue(gpu.cancels.get()>0);
                gpu.delayed.complete(Device.frame());assertThrows(ExecutionException.class,()->h.result().toCompletableFuture().get(3,TimeUnit.SECONDS));assertEquals(PhotoJobState.Stage.CANCELLED,h.state().stage());assertTrue(store.committedIds().isEmpty());assertEquals(1,gpu.calls.get());inputs.released();
            }
        }
    }
    @Test void requestTimeoutRejectsLatePixelsWithoutRetryOrPublication()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);
        try(var inputs=new Inputs();var gpu=new Device(null,true,false)) {
            var store=new DiskPhotoStore(directory.resolve("photos"));
            try(var pipeline=new CpuPhotoPipeline(limits(300_000_000),store,(a,b,c)->{throw new AssertionError("Expired photo published");},cpu,config(true),gpu)) {
                var h=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));long until=System.nanoTime()+2_000_000_000L;while(!h.state().stage().terminal() && System.nanoTime()<until)Thread.sleep(2);assertEquals(PhotoJobState.Stage.EXPIRED,h.state().stage());assertFalse(h.result().toCompletableFuture().isDone());
                gpu.delayed.completeExceptionally(new BackendException(BackendException.Code.TIMEOUT,"late timeout"));assertThrows(ExecutionException.class,()->h.result().toCompletableFuture().get(3,TimeUnit.SECONDS));assertEquals(1,gpu.calls.get());assertTrue(store.committedIds().isEmpty());inputs.released();
            }
        }
    }
    @Test void gpuWaitOccupiesNoCpuLaneAndSecondRequestCanFallbackAndFinish()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);var published=new AtomicInteger();
        try(var inputs=new Inputs();var gpu=new Device(null,true,true)) {
            var store=new DiskPhotoStore(directory.resolve("photos"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->{published.incrementAndGet();return CompletableFuture.completedFuture(null);},cpu,config(true),gpu)) {
                var first=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));var second=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),2,32L*1024*1024,inputs::prepare);second.result().toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertFalse(first.result().toCompletableFuture().isDone());assertEquals("CPU",store.loadCommitted(2).orElseThrow().metadata().get("backend"));assertEquals(1,published.get());gpu.delayed.complete(Device.frame());first.result().toCompletableFuture().get(3,TimeUnit.SECONDS);assertEquals("OPENCL",store.loadCommitted(1).orElseThrow().metadata().get("backend"));assertEquals(2,published.get());assertEquals(0,inputs.host.snapshot().live());
            }
        }
    }
    @Test void publicationFailureDoesNotRetryRenderOrChargeFilm()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);var calls=new AtomicInteger();UUID request=UUID.randomUUID(),owner=UUID.randomUUID(),camera=UUID.randomUUID();
        try(var inputs=new Inputs();var book=new CameraLedger(directory.resolve("ledger"),new CameraLedger.Limits(10,10,1048576));var io=new BudgetedWorkRunner(4,1_000_000,1_000_000,cpu);var gpu=new Device(null,false,false)) {
            book.reserve(request,camera,owner,2,0);book.bindOutput(request,1);var store=new DiskPhotoStore(directory.resolve("photos"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,publisher(book,io,calls,true),cpu,config(true),gpu)) {
                var h=pipeline.submit(owner,request,1,32L*1024*1024,inputs::prepare);assertThrows(ExecutionException.class,()->h.result().toCompletableFuture().get(3,TimeUnit.SECONDS));book.fail(request,"publication failed");assertEquals(1,gpu.calls.get());assertEquals(1,calls.get());assertEquals(0,book.snapshot().spent(camera));assertEquals(List.of(1),store.committedIds());inputs.released();
            }
        }
    }
    @Test void closingPipelineRetainsSourceUntilDeviceReplyAndCannotPublishLatePixels()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);
        try(var inputs=new Inputs();var gpu=new Device(null,true,false)) {
            var store=new DiskPhotoStore(directory.resolve("photos"));var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->{throw new AssertionError("Closed pipeline published");},cpu,config(true),gpu);
            try {
                var handle=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));pipeline.close();assertFalse(handle.result().toCompletableFuture().isDone());assertTrue(inputs.host.snapshot().live()>0);
                gpu.delayed.complete(Device.frame());assertThrows(ExecutionException.class,()->handle.result().toCompletableFuture().get(3,TimeUnit.SECONDS));assertTrue(store.committedIds().isEmpty());inputs.released();
            }finally{pipeline.close();gpu.close();}
        }
    }
    @Test void autoQueuesBehindBusyGpuAndRendersBothWithoutCpuFallback()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);var automatic=new GpuConfiguration(GpuConfiguration.Mode.AUTO,"Quadro P620",384,1,64,128,1000,true,1.2);
        try(var inputs=new Inputs();var gpu=new Device(null,true,false)) {
            var store=new DiskPhotoStore(directory.resolve("auto"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->CompletableFuture.completedFuture(null),cpu,automatic,gpu)) {
                var first=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));var second=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),2,32L*1024*1024,inputs::prepare);Thread.sleep(50);assertFalse(second.result().toCompletableFuture().isDone());assertEquals(1,gpu.calls.get());
                gpu.delayed.complete(Device.frame());first.result().toCompletableFuture().get(3,TimeUnit.SECONDS);second.result().toCompletableFuture().get(3,TimeUnit.SECONDS);
                var queued=store.loadCommitted(2).orElseThrow();assertEquals("OPENCL",queued.metadata().get("backend"));assertEquals("0",queued.metadata().get("gpuFallbackCount"));assertTrue(Long.parseLong(queued.metadata().get("gpuQueueNanos"))>0);assertEquals(2,gpu.calls.get());var rendered=store.loadCommitted(1).orElseThrow();assertEquals("OPENCL",rendered.metadata().get("backend"));assertTrue(rendered.metadata().get("backendDecision").equals("AUTO_GPU_FIRST"));assertEquals(0,inputs.host.snapshot().live());
            }
        }
    }
    @Test void cancelledGpuQueueEntryReleasesMemoryAndDoesNotBlockNextPhoto()throws Exception {
        var cpu=new CpuBudget(5000,100,System::nanoTime);var automatic=new GpuConfiguration(GpuConfiguration.Mode.AUTO,"Quadro P620",384,1,64,128,1000,true,1.2);
        try(var inputs=new Inputs();var gpu=new Device(null,true,false)) {
            var store=new DiskPhotoStore(directory.resolve("queued-cancel"));
            try(var pipeline=new CpuPhotoPipeline(limits(5_000_000_000L),store,(a,b,c)->CompletableFuture.completedFuture(null),cpu,automatic,gpu)) {
                var first=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),1,32L*1024*1024,inputs::prepare);assertTrue(gpu.entered.await(2,TimeUnit.SECONDS));
                var cancelled=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),2,32L*1024*1024,inputs::prepare);
                long until=System.nanoTime()+2_000_000_000L;while(cancelled.state().stage()!=PhotoJobState.Stage.RENDERING && System.nanoTime()<until)Thread.sleep(2);
                assertEquals(PhotoJobState.Stage.RENDERING,cancelled.state().stage());assertEquals(PhotoJobState.CancelResult.CANCELLED,cancelled.cancel());
                assertThrows(ExecutionException.class,()->cancelled.result().toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(1,gpu.calls.get());
                var third=pipeline.submit(UUID.randomUUID(),UUID.randomUUID(),3,32L*1024*1024,inputs::prepare);
                gpu.delayed.complete(Device.frame());first.result().toCompletableFuture().get(3,TimeUnit.SECONDS);third.result().toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertEquals(2,gpu.calls.get());assertTrue(store.loadCommitted(2).isEmpty());assertEquals("OPENCL",store.loadCommitted(3).orElseThrow().metadata().get("backend"));assertEquals(0,inputs.host.snapshot().live());
            }
        }
    }

}
