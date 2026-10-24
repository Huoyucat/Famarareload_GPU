package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.asset.Texture;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.schedule.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One GPU job, budgeted serialization/conversion; native waits occupy only the device worker. */
public final class OpenClPhotoRenderer implements PhotoRenderBackend {
    private final GpuDeviceProbe device;
    private final GpuConfiguration config;
    private final RenderMemoryBudget memory;
    private final BudgetedWorkRunner runner;
    private final LinkedHashMap<UUID,Map<String,String>> recent=new LinkedHashMap<>();
    private Job active;
    private boolean closed;
    private long cooldownUntil;
    private int deviceFailures;
    private final java.util.function.LongSupplier clock;
    private static final class Job {
        final Prepared prepared;final BooleanSupplier external;final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Result> result=new CompletableFuture<>();
        GpuSceneSerializer.Cursor cursor;GpuScene scene;RenderMemoryBudget.Lease io;
        long serializeStart,serializeNanos,cpuNanos,nativeCpuNanos;
        GpuDeviceProbe.PhotoFrame frame;
        Job(Prepared prepared,BooleanSupplier external){this.prepared=prepared;this.external=external;}
        boolean cancelled(){return cancelled.get() || external.getAsBoolean();}
        void check(){if(cancelled())throw new CancellationException("GPU photo cancelled");}
        void close(){try{if(cursor!=null){cursor.close();cursor=null;}}finally{try{if(scene!=null){scene.close();scene=null;}}finally{if(io!=null){io.close();io=null;}}}}
    }
    /** The caller owns the shared device and CPU runner, including their shutdown. */
    public OpenClPhotoRenderer(GpuDeviceProbe device,GpuConfiguration config,RenderMemoryBudget memory,BudgetedWorkRunner runner) {
        this(device,config,memory,runner,System::nanoTime);
    }
    OpenClPhotoRenderer(GpuDeviceProbe device,GpuConfiguration config,RenderMemoryBudget memory,BudgetedWorkRunner runner,java.util.function.LongSupplier clock) {
        this.clock=Objects.requireNonNull(clock);this.device=Objects.requireNonNull(device);this.config=Objects.requireNonNull(config);this.memory=Objects.requireNonNull(memory);this.runner=Objects.requireNonNull(runner);
    }
    @Override public synchronized Capabilities capabilities(){return new Capabilities("OPENCL",!closed && device.available() && clock.getAsLong()>=cooldownUntil,Set.of("OPAQUE_CUBE","1X","4X","LEGACY_ANGULAR","PERSPECTIVE","NEAREST","BILINEAR","MIPMAP","CUTOUT","TRANSLUCENT","ANIMATED","TRIANGLE","NON_CUBE","FLUID","FAR_LOD","CE","LEGACY_ENTITY"),closed?"CLOSED":clock.getAsLong()<cooldownUntil?"GPU_CIRCUIT_OPEN":device.available()?"":"DEVICE_UNAVAILABLE_OR_INITIALIZING");}
    @Override public synchronized Prepared prepare(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings){if(closed)throw new BackendException(BackendException.Code.CLOSED,"GPU renderer closed");return new Prepared(UUID.randomUUID(),scene,camera,settings);}
    @Override public synchronized CompletionStage<Result> submitAsync(Prepared prepared,BooleanSupplier cancelled) {
        Objects.requireNonNull(prepared);Objects.requireNonNull(cancelled);
        if(closed)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.CLOSED,"GPU renderer closed"));
        if(config.mode()==GpuConfiguration.Mode.OFF)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.UNAVAILABLE,"GPU_OFF"));
        if(clock.getAsLong()<cooldownUntil)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.UNAVAILABLE,"GPU_CIRCUIT_OPEN"));
        if(active!=null)return CompletableFuture.failedFuture(new RejectedExecutionException("One GPU photo may be active or pending"));
        var job=new Job(prepared,cancelled);active=job;serialize(job);return job.result.minimalCompletionStage();
    }
    private <T> T measured(Job job,java.util.function.Supplier<T> action) {
        var meter=ManagementFactory.getThreadMXBean();long start=meter.getCurrentThreadCpuTime();
        try{return action.get();}finally{job.cpuNanos+=Math.max(0,meter.getCurrentThreadCpuTime()-start);}
    }
    private void serialize(Job job) {
        try {
            runner.submit(()->measured(job,()->{
                job.check();
                if(job.cursor==null){job.serializeStart=System.nanoTime();job.cursor=GpuSceneSerializer.cursor(job.prepared.scene(),job.prepared.camera(),job.prepared.settings(),config,memory,job::cancelled);}
                if(!job.cursor.step(512,System.nanoTime()+1_000_000,System::nanoTime))return false;
                job.scene=job.cursor.takeResult();job.cursor=null;job.serializeNanos=System.nanoTime()-job.serializeStart;
                GpuPhotoSupport.require(job.scene);
                if(job.scene.metadata().hostReservationBytes()+3L*1024*1024>config.stagingMemoryMiB()*1024L*1024)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Photo conversion and readback exceed staging cap");
                job.io=memory.reserve("gpu-photo-conversion",1024*1024);return true;
            })).whenComplete((ready,error)->{
                if(error!=null){finish(job,null,error);return;}
                if(!ready){serialize(job);return;}
                nativeRender(job);
            });
        }catch(Throwable error){finish(job,null,error);}
    }
    private void nativeRender(Job job) {
        try {
            job.check();long sequence=device.operationSequence();device.renderPhoto(job.scene).whenComplete((frame,error)->{
                job.nativeCpuNanos=device.operationSequence()>sequence?device.lastOperationCpuNanos():0;job.frame=frame;
                if(error!=null){finish(job,null,error);return;}
                try {
                    runner.submit(()->measured(job,()->{
                        job.check();long conversionStart=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();float[] linear=frame.linear();int[] rgb=new int[16384];
                        for(int i=0;i<rgb.length;i++)rgb[i]=Texture.srgb(linear[i*3])<<16|Texture.srgb(linear[i*3+1])<<8|Texture.srgb(linear[i*3+2]);
                        var status=device.initialization().toCompletableFuture().getNow(null);var metadata=new LinkedHashMap<String,String>();
                        metadata.put("backend","OPENCL");metadata.put("gpuDevice",status==null || status.device()==null?"unknown":status.device().name());metadata.put("fallbackReason","");
                        metadata.put("serializeMs",Double.toString(job.serializeNanos/1e6));metadata.put("uploadMs",Double.toString(frame.uploadNanos()/1e6));metadata.put("kernelMs",Double.toString(frame.kernelNanos()/1e6));metadata.put("readbackMs",Double.toString(frame.readbackNanos()/1e6));
                        metadata.put("renderCpuNanos",Long.toString(job.cpuNanos+job.nativeCpuNanos+Math.max(0,ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-conversionStart)));metadata.put("vramPeak",Long.toString(frame.vramPeak()));metadata.put("textureCacheHit",Boolean.toString(frame.textureCacheHit()));metadata.put("geometryCacheHit",Boolean.toString(frame.geometryCacheHit()));
                        return new Result(rgb,metadata);
                    })).whenComplete((result,failure)->finish(job,result,failure));
                }catch(Throwable failure){finish(job,null,failure);}
            });
        }catch(Throwable error){finish(job,null,error);}
    }
    private void finish(Job job,Result result,Throwable error) {
        long cleanupStart=ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        try{job.close();}catch(Throwable cleanup){if(error==null)error=cleanup;else error.addSuppressed(cleanup);}
        long cleanupCpu=Math.max(0,ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime()-cleanupStart);job.cpuNanos+=cleanupCpu;device.accountCpuMaintenance(cleanupCpu);
        var diagnostic=new LinkedHashMap<String,String>();diagnostic.put("gpuDevice",device.initialization().toCompletableFuture().getNow(new GpuDeviceProbe.Status(false,null,"INITIALIZING",null)).device()==null?"UNAVAILABLE":device.initialization().toCompletableFuture().getNow(null).device().name());
        diagnostic.put("gpuOwnerPending",Boolean.toString(device.hasPendingWork()));
        diagnostic.put("serializeMs",Double.toString(job.serializeNanos/1e6));diagnostic.put("renderCpuNanos",Long.toString(job.cpuNanos+job.nativeCpuNanos));
        diagnostic.put("uploadMs",job.frame==null?"UNKNOWN_AFTER_GPU_FAILURE":Double.toString(job.frame.uploadNanos()/1e6));diagnostic.put("kernelMs",job.frame==null?"UNKNOWN_AFTER_GPU_FAILURE":Double.toString(job.frame.kernelNanos()/1e6));diagnostic.put("readbackMs",job.frame==null?"UNKNOWN_AFTER_GPU_FAILURE":Double.toString(job.frame.readbackNanos()/1e6));
        var buffers=device.buffers();diagnostic.put("vramPeak",Long.toString(buffers==null?0:buffers.peak()));
        synchronized(this){
            Throwable root=error;while(root instanceof CompletionException && root.getCause()!=null)root=root.getCause();
            if(root==null)deviceFailures=0;
            else if(root instanceof BackendException failure && (failure.code()==BackendException.Code.DEVICE_ERROR || failure.code()==BackendException.Code.OUT_OF_MEMORY || failure.code()==BackendException.Code.TIMEOUT)) {
                if(++deviceFailures>=3)cooldownUntil=clock.getAsLong()+Math.min(30_000_000_000L,5_000_000_000L*(deviceFailures-2));
            }
            if(active==job)active=null;recent.put(job.prepared.id(),Map.copyOf(diagnostic));while(recent.size()>16)recent.remove(recent.keySet().iterator().next());}
        if(error!=null)job.result.completeExceptionally(error);
        else try{job.check();job.result.complete(result);}catch(Throwable cancelled){job.result.completeExceptionally(cancelled);}
    }
    @Override public synchronized int activeJobs(){return active==null?0:1;}
    @Override public String deviceName(){var status=device.initialization().toCompletableFuture().getNow(null);return status==null || status.device()==null?"UNKNOWN":status.device().name();}
    @Override public long availableHostBytes(){var budget=memory.snapshot();return budget.maximum()-budget.live();}
    @Override public long availableVramBytes(){var budget=device.buffers();return budget==null?0:budget.maximum()-budget.live();}
    @Override public synchronized Map<String,String> diagnostics(UUID id){return recent.getOrDefault(id,Map.of());}
    @Override public synchronized void cancel(UUID id){if(active!=null && active.prepared.id().equals(id))active.cancelled.set(true);}
    @Override public synchronized void close(){if(closed)return;closed=true;if(active!=null)active.cancelled.set(true);}
}
