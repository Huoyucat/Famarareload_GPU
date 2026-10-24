package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.schedule.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;

/** Native initialization/waits never run on a game thread or occupy a CPU render lane. */
public final class GpuDeviceProbe implements AutoCloseable {
    public record Status(boolean available,GpuDevice device,String reason,GpuMemoryBudget.Snapshot buffers) {}
    public record Smoke(byte[] rgb,long kernelNanos,long readbackWallNanos,long wallNanos,long threadCpuNanos) {
        public Smoke {rgb=rgb.clone();}
        @Override public byte[] rgb(){return rgb.clone();}
    }
    public record CandidateBatch(int[] rows,long wallNanos) {
        public CandidateBatch{rows=rows.clone();}
        @Override public int[] rows(){return rows.clone();}
    }
    public record HitBatch(int[] ids,float[] hits,long wallNanos) {
        public HitBatch{ids=ids.clone();hits=hits.clone();}
        @Override public int[] ids(){return ids.clone();}
        @Override public float[] hits(){return hits.clone();}
    }
    public record HitListBatch(int[] ids,float[] hits,int[] counts,long wallNanos) {
        public HitListBatch{ids=ids.clone();hits=hits.clone();counts=counts.clone();}
        @Override public int[] ids(){return ids.clone();}
        @Override public float[] hits(){return hits.clone();}
        @Override public int[] counts(){return counts.clone();}
    }
    public record PhotoFrame(float[] linear,long uploadNanos,long kernelNanos,long readbackNanos,long wallNanos,long threadCpuNanos,boolean textureCacheHit,long vramPeak,boolean geometryCacheHit) {
        public PhotoFrame(float[] linear,long uploadNanos,long kernelNanos,long readbackNanos,long wallNanos,long threadCpuNanos,boolean textureCacheHit,long vramPeak){this(linear,uploadNanos,kernelNanos,readbackNanos,wallNanos,threadCpuNanos,textureCacheHit,vramPeak,false);}
        public PhotoFrame{linear=linear.clone();}
        @Override public float[] linear(){return linear.clone();}
    }
    interface Session extends AutoCloseable {
        GpuDevice device();GpuMemoryBudget.Snapshot memory();Smoke smoke();
        default void warm(){}
        default CandidateBatch candidates(GpuScene scene,int[] xyz){throw new BackendException(BackendException.Code.UNSUPPORTED,"Index probe unavailable");}
        default HitBatch trace(GpuScene scene,float[] rays){throw new BackendException(BackendException.Code.UNSUPPORTED,"Trace probe unavailable");}
        default HitListBatch hitLists(GpuScene scene,float[] rays){throw new BackendException(BackendException.Code.UNSUPPORTED,"Hit list probe unavailable");}
        default PhotoFrame photo(GpuScene scene){throw new BackendException(BackendException.Code.UNSUPPORTED,"Photo renderer unavailable");}
        @Override void close();
    }
    @FunctionalInterface interface Factory {Session open(GpuConfiguration config,RenderMemoryBudget host);}
    private final GpuConfiguration config;
    private final CpuBudget cpu;
    private final RenderMemoryBudget host;
    private final Factory factory;
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private final ScheduledThreadPoolExecutor worker,watchdog;
    private long sequence;
    private volatile long lastNativeCpu;
    private final CompletableFuture<Status> initialized=new CompletableFuture<>();
    private final CompletableFuture<Void> disposed=new CompletableFuture<>();
    private Session session;
    private record Call<T>(java.util.function.Function<Session,T> action,AutoCloseable resources) {}
    private static final class Work<T> {
        final Call<T> call;final CompletableFuture<T> result=new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        volatile long cpuStart=-1,ownerId,cpuNanos;
        Work(Call<T> call){this.call=call;}
        void release()throws Exception {if(released.compareAndSet(false,true))call.resources.close();}
    }
    private Work<?> pending;
    private volatile boolean closed;

    public GpuDeviceProbe(GpuConfiguration config,CpuBudget cpu,RenderMemoryBudget host){this(config,cpu,host,OpenClSession::open);}
    public GpuDeviceProbe(GpuConfiguration config,CpuBudget cpu,RenderMemoryBudget host,java.nio.file.Path ownerLock){this(config,cpu,host,(settings,memory)->OpenClSession.open(settings,memory,ownerLock));}
    GpuDeviceProbe(GpuConfiguration config,CpuBudget cpu,RenderMemoryBudget host,Factory factory) {
        this.config=Objects.requireNonNull(config);this.cpu=Objects.requireNonNull(cpu);this.host=Objects.requireNonNull(host);this.factory=Objects.requireNonNull(factory);
        if(config.mode()==GpuConfiguration.Mode.OFF){worker=null;watchdog=null;initialized.complete(unavailable("GPU_OFF"));return;}
        if(!meter.isCurrentThreadCpuTimeSupported()){worker=null;watchdog=null;initialized.complete(unavailable("Thread CPU measurement unavailable"));return;}
        try{if(!meter.isThreadCpuTimeEnabled())meter.setThreadCpuTimeEnabled(true);}
        catch(SecurityException | UnsupportedOperationException failure){worker=null;watchdog=null;initialized.complete(unavailable(failure.toString()));return;}
        worker=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"Famara-opencl-device");thread.setDaemon(true);return thread;});
        worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        watchdog=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"Famara-opencl-timeout");thread.setDaemon(true);return thread;});
        watchdog.setRemoveOnCancelPolicy(true);watchdog.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        var initializationTimer=watchdog.schedule(()->timed(()->{if(initialized.complete(unavailable("OPENCL_INITIALIZATION_TIMEOUT")))close();}),config.kernelTimeoutMillis(),TimeUnit.MILLISECONDS);
        initialized.whenComplete((value,error)->initializationTimer.cancel(false));try{worker.execute(this::initialize);}catch(RejectedExecutionException error){if(!closed)throw error;}
    }
    private static Status unavailable(String reason){return new Status(false,null,reason,null);}
    public CompletionStage<Status> initialization(){return initialized.minimalCompletionStage();}
    public CompletionStage<Void> whenClosed(){return disposed.minimalCompletionStage();}
    private void initialize() {
        if(closed)return;
        var allowance=cpu.reserve(1_000_000);
        if(allowance.isEmpty()){reschedule(this::initialize,null);return;}
        long start=meter.getCurrentThreadCpuTime();Session created=null;Status status;
        try {
            created=factory.open(config,host);created.smoke();created.warm();
            status=new Status(true,created.device(),"OPENCL_DEVICE_READY",created.memory());
        }catch(Throwable failure) {
            status=unavailable(failure.toString());
            if(created!=null)try{created.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}created=null;
        }finally{allowance.get().settle(Math.max(0,meter.getCurrentThreadCpuTime()-start));}
        if(!status.available() && status.reason().contains("GPU_OWNER_BUSY") && !closed){
            try{worker.schedule(this::initialize,50,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){}return;
        }
        boolean discard;
        synchronized(this){discard=closed || initialized.isDone();if(!discard)session=created;}
        // A native close can wait for a driver. Never hold the monitor needed by game-thread close().
        if(discard){if(created!=null)created.close();return;}
        initialized.complete(status);
    }
    public CompletionStage<Smoke> smoke() {
        return submitNative(()->new Call<>(Session::smoke,()->{}));
    }
    public CompletionStage<CandidateBatch> probeCandidates(GpuScene scene,int[] xyz) {
        if(xyz.length==0 || xyz.length%3!=0 || xyz.length/3>16384)throw new IllegalArgumentException("Expected 1..16384 frozen cell queries");
        return submitNative(()->{
            var pinned=scene.retain();RenderMemoryBudget.Lease input=null;
            try {
                long bytes=xyz.length*4L;
                if(scene.metadata().hostReservationBytes()+bytes*4>config.stagingMemoryMiB()*1024L*1024)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Query staging exceeds configured cap");
                input=host.reserve("gpu-query-input",bytes);int[] frozen=xyz.clone();var reserved=input;
                return new Call<>(session->session.candidates(scene,frozen),()->{try{reserved.close();}finally{pinned.close();}});
            }catch(RuntimeException | Error failure){if(input!=null)input.close();pinned.close();throw failure;}
        });
    }
    public CompletionStage<HitBatch> probeRays(GpuScene scene,float[] rays) {
        if(rays.length==0 || rays.length%8!=0 || rays.length/8>8192)throw new IllegalArgumentException("Expected 1..8192 frozen rays");
        for(int i=0;i<rays.length;i++)if(!Float.isFinite(rays[i]))throw new IllegalArgumentException("Non-finite ray");
        for(int i=0;i<rays.length;i+=8)if(rays[i+7]<=0 || (rays[i+4]==0 && rays[i+5]==0 && rays[i+6]==0))throw new IllegalArgumentException("Invalid ray");
        return submitNative(()->{
            var pinned=scene.retain();RenderMemoryBudget.Lease input=null;
            try {
                long bytes=rays.length*4L;
                if(scene.metadata().hostReservationBytes()+bytes*3>config.stagingMemoryMiB()*1024L*1024)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Ray staging exceeds configured cap");
                input=host.reserve("gpu-ray-input",bytes);float[] frozen=rays.clone();var reserved=input;
                return new Call<>(session->session.trace(scene,frozen),()->{try{reserved.close();}finally{pinned.close();}});
            }catch(RuntimeException | Error failure){if(input!=null)input.close();pinned.close();throw failure;}
        });
    }
    public CompletionStage<HitListBatch> probeHitLists(GpuScene scene,float[] rays) {
        if(rays.length==0 || rays.length%8!=0 || rays.length/8>128)throw new IllegalArgumentException("Expected 1..128 diagnostic rays");
        for(float value:rays)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite diagnostic ray");
        for(int i=0;i<rays.length;i+=8)if(rays[i+7]<=0 || (rays[i+4]==0 && rays[i+5]==0 && rays[i+6]==0))throw new IllegalArgumentException("Invalid diagnostic ray");
        return submitNative(()->{
            var pinned=scene.retain();RenderMemoryBudget.Lease input=null;
            try {
                long bytes=rays.length*4L,output=rays.length/8*4112L;
                if(scene.metadata().hostReservationBytes()+bytes+output>config.stagingMemoryMiB()*1024L*1024)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Hit lists exceed staging cap");
                input=host.reserve("gpu-hit-list-input",bytes);float[] frozen=rays.clone();var reserved=input;
                return new Call<>(session->session.hitLists(scene,frozen),()->{try{reserved.close();}finally{pinned.close();}});
            }catch(RuntimeException | Error failure){if(input!=null)input.close();pinned.close();throw failure;}
        });
    }
    public CompletionStage<PhotoFrame> renderPhoto(GpuScene scene) {
        return submitNative(()->{
            GpuPhotoSupport.require(scene);
            if(scene.metadata().hostReservationBytes()+3L*1024*1024>config.stagingMemoryMiB()*1024L*1024)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Photo readback staging exceeds cap");
            var pinned=scene.retain();return new Call<>(session->session.photo(scene),pinned);
        });
    }
    private <T> CompletionStage<T> submitNative(java.util.function.Supplier<Call<T>> create) {
        Work<T> work;
        synchronized(this) {
            if(closed)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.CLOSED,"GPU probe closed"));
            if(worker==null)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.UNAVAILABLE,initialized.getNow(unavailable("GPU_OFF")).reason()));
            if(pending!=null)return CompletableFuture.failedFuture(new RejectedExecutionException("GPU supports one pending/active native job"));
            try{work=new Work<>(create.get());}catch(Throwable error){return CompletableFuture.failedFuture(error);}pending=work;sequence++;
        }
        initialization().whenComplete((status,error)->{
            if(error!=null || !status.available()){finish(work,null,error==null?new BackendException(BackendException.Code.UNAVAILABLE,status.reason()):error);return;}
            try{worker.execute(()->nativeStep(work));}catch(RejectedExecutionException failure){finish(work,null,failure);}
        });
        try {
            var timer=watchdog.schedule(()->timed(()->{
                if(work.result.completeExceptionally(new BackendException(BackendException.Code.TIMEOUT,"GPU operation timed out; native owner remains pinned until return")))close();
            }),config.kernelTimeoutMillis(),TimeUnit.MILLISECONDS);
            work.result.whenComplete((value,error)->timer.cancel(false));
        }catch(RejectedExecutionException ignored){/* close() already completed or will complete this work. */}
        return work.result.minimalCompletionStage();
    }
    private <T> void nativeStep(Work<T> work) {
        if(closed){finish(work,null,new CancellationException("GPU probe closed"));return;}
        var allowance=cpu.reserve(1_000_000);
        if(allowance.isEmpty()){reschedule(()->nativeStep(work),work.result);return;}
        long start=meter.getCurrentThreadCpuTime();work.ownerId=Thread.currentThread().threadId();work.cpuStart=start;T value=null;Throwable error=null;
        try{value=work.call.action.apply(session);}catch(Throwable failure){error=failure;}
        try{work.release();}catch(Throwable cleanup){if(error==null)error=cleanup;else error.addSuppressed(cleanup);}
        work.cpuNanos=Math.max(0,meter.getCurrentThreadCpuTime()-start);allowance.get().settle(work.cpuNanos);
        finish(work,value,error);
    }
    private <T> void finish(Work<T> work,T value,Throwable error) {
        try{work.release();}catch(Throwable cleanup){if(error==null)error=cleanup;else error.addSuppressed(cleanup);}
        synchronized(this){if(pending==work){lastNativeCpu=work.cpuNanos;pending=null;}}
        if(error!=null)work.result.completeExceptionally(error);
        else if(closed)work.result.completeExceptionally(new CancellationException("GPU probe closed"));
        else work.result.complete(value);
    }
    private void timed(Runnable action){long start=meter.getCurrentThreadCpuTime();try{action.run();}finally{cpu.chargeMaintenance(Math.max(0,meter.getCurrentThreadCpuTime()-start));}}
    void accountCpuMaintenance(long nanos){cpu.chargeMaintenance(nanos);}
    public synchronized boolean hasPendingWork(){return pending!=null;}
    public synchronized long operationSequence(){return sequence;}
    /** Actual thread CPU, including a partial sample if a timeout precedes native return. */
    public synchronized long lastOperationCpuNanos(){
        if(pending==null)return lastNativeCpu;
        if(pending.cpuStart<0)return 0;
        return Math.max(pending.cpuNanos,Math.max(0,meter.getThreadCpuTime(pending.ownerId)-pending.cpuStart));
    }
    int pendingTimeouts(){return watchdog==null?0:watchdog.getQueue().size();}
    private void reschedule(Runnable action,CompletableFuture<?> result) {
        try{worker.schedule(action,Math.max(1_000_000,Math.min(50_000_000,cpu.retryAfterNanos(1_000_000))),TimeUnit.NANOSECONDS);}
        catch(RejectedExecutionException failure){if(result!=null)result.completeExceptionally(failure);}
    }
    /** Only reads a copied Java ledger; it never invokes a native device query. */
    public synchronized GpuMemoryBudget.Snapshot buffers(){return session==null?null:session.memory();}
    public boolean available(){return !closed && initialized.getNow(unavailable("INITIALIZING")).available();}
    @Override public synchronized void close() {
        if(closed)return;closed=true;initialized.complete(unavailable("CLOSED"));
        if(pending!=null)pending.result.completeExceptionally(new CancellationException("GPU probe closed"));
        if(worker==null){disposed.complete(null);return;}
        worker.execute(()->{
            Throwable failure=null;
            try{if(pending!=null)pending.release();}catch(Throwable error){failure=error;}
            finally{pending=null;}
            try{if(session!=null)session.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            finally{session=null;}
            if(failure==null)disposed.complete(null);else disposed.completeExceptionally(failure);
        });worker.shutdown();watchdog.shutdownNow();
    }
}
