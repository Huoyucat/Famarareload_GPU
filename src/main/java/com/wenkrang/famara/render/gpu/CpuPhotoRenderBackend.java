package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.schedule.BudgetedWorkRunner;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Thin adapter: reference pixels and existing CPU accounting remain authoritative. */
public final class CpuPhotoRenderBackend implements PhotoRenderBackend {
    private final BudgetedWorkRunner runner;
    private final Map<UUID,Job> jobs=new HashMap<>();
    private boolean closed;
    private static final class Job {
        final Prepared prepared;
        final CpuPhotoRenderer renderer;
        final CpuPhotoRenderer.Cursor cursor;
        final BooleanSupplier external;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Result> result=new CompletableFuture<>();
        Job(Prepared prepared,BooleanSupplier external) {
            this.prepared=prepared;this.external=external;
            renderer=referenceRenderer(prepared.scene(),prepared.camera(),prepared.settings());cursor=renderer.cursor();
        }
        boolean cancelled(){return cancelled.get() || external.getAsBoolean();}
    }
    public CpuPhotoRenderBackend(BudgetedWorkRunner runner){this.runner=Objects.requireNonNull(runner);}
    /** Used by the existing pipeline to retain its original two-lane ownership/scheduler. */
    public static CpuPhotoRenderer referenceRenderer(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings) {
        return new CpuPhotoRenderer(scene,camera,settings);
    }
    @Override public synchronized Capabilities capabilities() {
        return new Capabilities("CPU",!closed,Set.of("REFERENCE_ALL"),closed?"CLOSED":"");
    }
    @Override public synchronized Prepared prepare(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings) {
        if(closed)throw new BackendException(BackendException.Code.CLOSED,"CPU backend closed");
        return new Prepared(UUID.randomUUID(),scene,camera,settings);
    }
    @Override public synchronized CompletionStage<Result> submitAsync(Prepared prepared,BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled);
        if(closed)return CompletableFuture.failedFuture(new BackendException(BackendException.Code.CLOSED,"CPU backend closed"));
        if(jobs.containsKey(prepared.id()))return CompletableFuture.failedFuture(new IllegalStateException("Duplicate backend job"));
        Job job=new Job(prepared,cancelled);jobs.put(prepared.id(),job);schedule(job);return job.result.minimalCompletionStage();
    }
    private void schedule(Job job) {
        try {
            runner.submit(()->{
                if(job.cancelled())throw new CancellationException("CPU backend request cancelled");
                var batch=job.renderer.renderBatch(job.cursor,256,System.nanoTime()+1_000_000,System::nanoTime,job::cancelled);
                if(batch.status()==CpuPhotoRenderer.Status.CANCELLED || job.cancelled())throw new CancellationException("CPU backend request cancelled");
                return batch.status()==CpuPhotoRenderer.Status.DONE?new Result(job.cursor.copyRgb(),Map.of("backend","CPU")):null;
            }).whenComplete((result,error)->{
                // Runner has left the action and settled CPU before this callback; lease ownership can now end.
                if(error!=null){finish(job,null,error);return;}
                if(result!=null){finish(job,result,null);return;}
                schedule(job);
            });
        }catch(Throwable error){finish(job,null,error);}
    }
    private void finish(Job job,Result result,Throwable error) {
        synchronized(this){jobs.remove(job.prepared.id(),job);}
        if(error!=null)job.result.completeExceptionally(error);
        else try {
            if(job.cancelled())job.result.completeExceptionally(new CancellationException("CPU backend request cancelled"));
            else job.result.complete(result);
        }catch(Throwable cancellationFailure){job.result.completeExceptionally(cancellationFailure);}
    }
    @Override public synchronized void cancel(UUID id){Job job=jobs.get(id);if(job!=null)job.cancelled.set(true);}
    @Override public synchronized void close(){if(closed)return;closed=true;jobs.values().forEach(job->job.cancelled.set(true));}
}
