package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.kernel.*;
import com.wenkrang.famara.render.scene.SceneSnapshot;
import com.wenkrang.famara.render.output.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.lang.management.*;

/**
 * 无世界访问的异步管线。Publisher 是平台端口；离线测试不能证明真实地图已经安装。
 */
public final class CpuPhotoPipeline implements AutoCloseable {
    public record Limits(int workers, int maxJobs, int perPlayer, long sceneMemoryBytes, long dispatchIntervalNanos,
                         long quantumCpuNanos, long maxBatchCpuNanos, double cpuMillisPerSecond, double burstMillis,
                         int batchPixels, long timeoutNanos,Long scanBatchCpuNanos) {
        public Limits(int workers,int maxJobs,int perPlayer,long sceneMemoryBytes,long dispatchIntervalNanos,long quantumCpuNanos,long maxBatchCpuNanos,double cpuMillisPerSecond,double burstMillis,int batchPixels,long timeoutNanos){this(workers,maxJobs,perPlayer,sceneMemoryBytes,dispatchIntervalNanos,quantumCpuNanos,maxBatchCpuNanos,cpuMillisPerSecond,burstMillis,batchPixels,timeoutNanos,null);}
        public Limits {
            scanBatchCpuNanos=scanBatchCpuNanos==null?maxBatchCpuNanos:scanBatchCpuNanos;
            if(scanBatchCpuNanos<quantumCpuNanos || scanBatchCpuNanos>100000000 || scanBatchCpuNanos>burstMillis*1e6)throw new IllegalArgumentException("扫描批次时间片超限");
            if (workers <= 0 || workers > 64 || maxJobs <= 0 || maxJobs > 10000 || perPlayer <= 0 || perPlayer > maxJobs
                    || sceneMemoryBytes <= 0 || dispatchIntervalNanos < 1_000_000 || quantumCpuNanos <= 0 || maxBatchCpuNanos < quantumCpuNanos
                    || maxBatchCpuNanos > burstMillis * 1e6 || batchPixels <= 0 || batchPixels > 16384 || timeoutNanos <= 0
                    || !Double.isFinite(cpuMillisPerSecond) || cpuMillisPerSecond <= 0 || !Double.isFinite(burstMillis) || burstMillis <= 0)
                throw new IllegalArgumentException("管线限额必须显式给出且有效");
        }
    }

    public record Prepared(SceneSnapshot scene, Camera camera, CpuPhotoRenderer.Settings settings,
                           MapPaletteSnapshot palette, AssetRepository.Lease lease,Map<String,String> sourceMetadata,MemoryScope memory) {
        public Prepared(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings,MapPaletteSnapshot palette,AssetRepository.Lease lease,Map<String,String> metadata){this(scene,camera,settings,palette,lease,metadata,null);}
        public Prepared(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings,MapPaletteSnapshot palette,AssetRepository.Lease lease) {
            this(scene,camera,settings,palette,lease,Map.of(),null);
        }
        public Prepared {
            Objects.requireNonNull(scene);
            Objects.requireNonNull(camera);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(palette);
            Objects.requireNonNull(lease);sourceMetadata=Map.copyOf(sourceMetadata);
            if(sourceMetadata.size()>64 || sourceMetadata.entrySet().stream().anyMatch(e->e.getKey().length()>64 || e.getValue().length()>512))
                throw new IllegalArgumentException("场景来源元数据超过限额");
            if (scene.assets() != lease.generation()) throw new IllegalArgumentException("场景与资源租约不一致");
        }
    }

    public record PreparationProgress(String phase,long completed,long total,String detail) {
        public PreparationProgress{Objects.requireNonNull(phase);Objects.requireNonNull(detail);if(phase.length()>64 || detail.length()>384)throw new IllegalArgumentException("准备摘要过长");}
        public String message(){return phase+(total>0?" "+completed+"/"+total:"")+(detail.isEmpty()?"":"；"+detail);}
    }
    public enum PreparationStep { CONTINUE, WAITING, DONE }
    public interface Preparation extends AutoCloseable {
        /** 在任何快照/场景大分配前验证调用方已提供的保守工作内存预约。 */
        default long minimumMemoryBytes(){return 0;}
        default void wakeup(Runnable wakeup) {}
        default boolean scanning(){return false;}
        /** 只由计算owner读取，再发布不可变摘要；UI不得直接读准备器。 */
        default PreparationProgress progress(){return new PreparationProgress("准备中",0,0,"");}
        PreparationStep step(int operations,long deadlineNanos) throws Exception;
        Prepared result();
        @Override default void close() {}
    }
    private static Preparation once(Supplier<Prepared> supplier) {
        return new Preparation() {
            Prepared prepared;
            public PreparationStep step(int operations,long deadline){prepared=Objects.requireNonNull(supplier.get());return PreparationStep.DONE;}
            public Prepared result(){return prepared;}
        };
    }
    /** 平台先完成可回滚发布，再抢占不可取消的最终持久结算；结算开始后等待明确回执。 */
    public interface CommitGate extends BooleanSupplier {boolean beginSettlement();}
    @FunctionalInterface
    public interface Publisher {
        CompletionStage<Void> publish(DiskPhotoStore.Stored stored, ImmutablePhoto photo, BooleanSupplier stillCurrent);
    }

    public record Result(DiskPhotoStore.Stored stored, long cpuNanos, String cpuMeasurement) {
    }

    public final class Handle {
        private final Context context;

        private Handle(Context context) {
            this.context = context;
        }

        public PhotoJobState.State state() {
            return context.state.state();
        }

        public int mapId(){return context.mapId;}
        public PreparationProgress preparationProgress(){return context.progress;}
        public String progressMessage(){var stage=context.state.state().stage();return stage==PhotoJobState.Stage.CAPTURING?"CAPTURING · "+context.progress.message():stage==PhotoJobState.Stage.RENDERING?"RENDERING · "+completedPixels()+"/16384，渲染路数 "+(context.lanes==null?0:context.lanes.length):stage.toString();}
        public String diagnostics(){return context.diagnostics();}
        public int completedPixels(){WorkToken[] lanes=context.lanes;if(lanes==null)return 0;int pixels=0;for(var lane:lanes)pixels+=lane.cursor.completedPixels();return pixels;}
        public int peakRenderLanes(){return context.peakRenderLanes;}
        public long renderWallNanos(){return context.renderFinished==0?0:context.renderFinished-context.renderStarted;}
        public long renderCpuNanos(){return context.renderCpu.get();}
        public boolean bindOutput(int mapId) {
            if(mapId<0)throw new IllegalArgumentException("地图ID不能为负");
            synchronized(context.scheduling) {
                if(context.state.state().stage().terminal())return false;
                if(context.mapId==mapId)return true;
                if(context.mapId>=0)throw new IllegalStateException("地图ID已经绑定");
                context.mapId=mapId;
            }
            offer(context);return true;
        }
        public void fail(Throwable error){context.finishFailure(error);}

        public CompletionStage<Result> result() {
            return context.result.minimalCompletionStage();
        }

        public PhotoJobState.CancelResult cancel() {
            var cancelled = context.state.cancel("调用方取消");
            if (cancelled == PhotoJobState.CancelResult.CANCELLED)
                context.finishFailure(new CancellationException("照片取消"));
            return cancelled;
        }
    }

    private final Limits limits;
    private final DiskPhotoStore store;
    private final Publisher publisher;
    private final AdmissionController admission;
    private final CpuBudget budget;
    private final LeakyWorkQueue<WorkToken> queue;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor ticker;
    private final Semaphore slots;
    private final ConcurrentHashMap<UUID, Context> jobs = new ConcurrentHashMap<>();
    private final LinkedHashMap<UUID, Handle> recent = new LinkedHashMap<>();
    private final Object submissions = new Object();
    private final ThreadMXBean meter = ManagementFactory.getThreadMXBean();
    private final boolean cpuTime;
    private volatile boolean closed;
    private volatile int batchPixels;

    private final class Context {
        final UUID id, player;
        final int requestedMapId;
        volatile int mapId;
        final Object scheduling=new Object();
        final WorkToken control;
        volatile WorkToken[] lanes;
        boolean lanesActivated;
        volatile int activeRenderLanes,peakRenderLanes;
        volatile long renderStarted,renderFinished;
        final AtomicLong renderCpu=new AtomicLong();
        final long deadline, reservedBytes;
        final long started=System.nanoTime();
        volatile long captureStarted,captureFinished;
        final AtomicLong preparationCpu=new AtomicLong();
        volatile PreparationProgress progress=new PreparationProgress("等待拍摄预约",0,0,"");
        String diagnostics(){return "request="+id+"，stage="+state.state().stage()+"，最后子阶段="+progress.message()+"，wallMs="+(System.nanoTime()-started)/1000000+"，cpuMs="+cpuNanos.get()/1000000+"，captureCpuMs="+preparationCpu.get()/1000000;}
        final PhotoJobState state = new PhotoJobState();
        final CompletableFuture<Result> result = new CompletableFuture<>();
        final AdmissionController.Permit permit;
        final AtomicInteger running = new AtomicInteger();
        final AtomicBoolean cleaned = new AtomicBoolean();
        final Handle handle = new Handle(this);
        final AtomicLong cpuNanos = new AtomicLong();
        Preparation preparation;
        volatile Prepared prepared;
        MemoryScope memory;
        String assetFingerprint;
        boolean outputOnly;
        volatile Throwable failure;
        volatile DiskPhotoStore.Stored storedResult;
        volatile boolean usedWall;
        boolean settling;
        final CommitGate gate=new CommitGate() {
            public boolean getAsBoolean(){return valid();}
            public boolean beginSettlement(){synchronized(scheduling){if(!valid() || state.state().stage()!=PhotoJobState.Stage.COMMITTING)return false;settling=true;return true;}}
        };
        boolean receiptCurrent(){synchronized(scheduling){return settling || valid();}}

        Context(UUID id, UUID player, int mapId, long reserve, Preparation prepare, AdmissionController.Permit permit) {
            this.id = id;control=new WorkToken(this,-1);
            this.player = player;
            this.mapId=mapId;this.requestedMapId=mapId;
            this.reservedBytes = reserve;
            this.preparation = prepare;
            this.permit = permit;
            deadline = System.nanoTime() + limits.timeoutNanos;
        }

        boolean valid() {
            return !closed && !state.state().stage().terminal() && System.nanoTime() - deadline < 0;
        }

        boolean isSettling(){synchronized(scheduling){return settling;}}
        void settlementFailed(Throwable failure){synchronized(scheduling){settling=false;}finishFailure(failure);}
        void finishFailure(Throwable failure) {
            synchronized(scheduling){if(settling && !state.state().stage().terminal())return;if(this.failure==null)this.failure=failure;state.fail(this.failure.toString());}
            queue.remove(control.id);WorkToken[] group=lanes;if(group!=null)for(var lane:group)queue.remove(lane.id);
            cleanup();
        }

        void recordCleanup(Throwable error){if(failure==null){failure=error;state.fail(error.toString());}else if(failure!=error)failure.addSuppressed(error);}
        void cleanup() {
            synchronized(scheduling) {
                if(!state.state().stage().terminal() || running.get()!=0 || !cleaned.compareAndSet(false,true))return;
            }
            Prepared owned=prepared;prepared=null;
            Preparation pending=preparation;preparation=null;
            try { if(owned!=null)owned.lease.close(); }
            catch(Throwable error){recordCleanup(error);}
            try { if(pending!=null)pending.close(); }
            catch(Throwable error){recordCleanup(error);}
            lanes=null;
            try{if(memory!=null)memory.close();}catch(Throwable error){recordCleanup(error);}finally{memory=null;permit.close();}
            synchronized (submissions) {
                jobs.remove(id,this);
                recent.put(id, handle);
                while (recent.size() > limits.maxJobs * 4) recent.remove(recent.keySet().iterator().next());
            }
            if (state.state().stage() == PhotoJobState.Stage.SUCCEEDED)
                result.complete(new Result(storedResult, cpuNanos.get(), usedWall ? "WALL_FALLBACK" : "THREAD_CPU"));
            else
                result.completeExceptionally(failure == null ? new CancellationException(state.state().reason()) : failure);
        }
    }

    private final class WorkToken {
        final Context context;final UUID id;final int lane;
        boolean queued,running,needsDispatch,done;
        CpuPhotoRenderer renderer;CpuPhotoRenderer.Cursor cursor;
        WorkToken(Context context,int lane){this.context=context;this.lane=lane;id=lane<0?context.id:UUID.randomUUID();}
    }
    @FunctionalInterface interface RenderProbe {void entered(UUID request,int lane);}
    private final RenderProbe probe;
    @FunctionalInterface interface RendererFactory {CpuPhotoRenderer create(SceneSnapshot scene,Camera camera,CpuPhotoRenderer.Settings settings);}
    private final RendererFactory rendererFactory;
    public CpuPhotoPipeline(Limits limits,DiskPhotoStore store,Publisher publisher) {
        this(limits,store,publisher,new CpuBudget(limits.cpuMillisPerSecond,limits.burstMillis,System::nanoTime));
    }

    public CpuPhotoPipeline(Limits limits,DiskPhotoStore store,Publisher publisher,CpuBudget sharedBudget) {
        this(limits,store,publisher,sharedBudget,(request,lane)->{});
    }
    CpuPhotoPipeline(Limits limits,DiskPhotoStore store,Publisher publisher,CpuBudget sharedBudget,RenderProbe probe) {
        this(limits,store,publisher,sharedBudget,probe,CpuPhotoRenderer::new);
    }
    CpuPhotoPipeline(Limits limits,DiskPhotoStore store,Publisher publisher,CpuBudget sharedBudget,RenderProbe probe,RendererFactory rendererFactory) {
        this.probe=Objects.requireNonNull(probe);this.rendererFactory=Objects.requireNonNull(rendererFactory);
        this.limits = Objects.requireNonNull(limits);batchPixels=limits.batchPixels;
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        admission = new AdmissionController(new AdmissionController.Limits(limits.maxJobs, limits.perPlayer, limits.sceneMemoryBytes));
        budget = Objects.requireNonNull(sharedBudget);
        budget.retryAfterNanos(limits.quantumCpuNanos);
        queue = new LeakyWorkQueue<>(Math.multiplyExact(limits.maxJobs,2), limits.dispatchIntervalNanos, limits.quantumCpuNanos, limits.maxBatchCpuNanos,true);
        slots = new Semaphore(limits.workers);
        AtomicInteger names = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "Famara-v2-cpu-" + names.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        workers = new ThreadPoolExecutor(limits.workers, limits.workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(limits.workers), factory);
        ticker = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "Famara-v2-dispatch");
            t.setDaemon(true);
            return t;
        });
        ticker.setRemoveOnCancelPolicy(true);
        boolean supported = meter.isCurrentThreadCpuTimeSupported();
        if (supported) try {
            if (!meter.isThreadCpuTimeEnabled()) meter.setThreadCpuTimeEnabled(true);
        } catch (SecurityException | UnsupportedOperationException e) {
            supported = false;
        }
        cpuTime = supported;
        // 仅此一个周期任务，用户请求不直接进入 ScheduledThreadPoolExecutor 的延时队列。
        ticker.scheduleWithFixedDelay(this::tick, 0, limits.dispatchIntervalNanos, TimeUnit.NANOSECONDS);
    }

    public Handle submit(UUID player,UUID request,int mapId,long memoryReservation,Supplier<Prepared> prepare) {
        if(mapId<0)throw new IllegalArgumentException("地图ID不能为负");
        return submitInternal(player,request,mapId,memoryReservation,once(prepare));
    }
    public Handle submitDeferred(UUID player,UUID request,long memoryReservation,Preparation prepare) {
        return submitInternal(player,request,-1,memoryReservation,prepare);
    }
    private Handle submitInternal(UUID player,UUID request,int mapId,long memoryReservation,Preparation prepare) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(request);
        Objects.requireNonNull(prepare);
        synchronized (submissions) {
            Handle existing = recent.get(request);
            if (existing != null) {
                if (!existing.context.player.equals(player) || existing.context.requestedMapId != mapId)
                    throw new RejectedExecutionException("请求身份冲突");
                return existing;
            }
            Context active = jobs.get(request);
            if (active != null) {
                if (!active.player.equals(player) || active.requestedMapId != mapId)
                    throw new RejectedExecutionException("请求身份冲突");
                return active.handle;
            }
            long required=prepare.minimumMemoryBytes();
            if(required<0 || memoryReservation<required)throw new RejectedExecutionException("准备阶段工作内存预约不足，至少需要："+required);
            var accepted = admission.admit(player, request, memoryReservation);
            if (!accepted.accepted()) throw new RejectedExecutionException("照片未受理：" + accepted.rejection());
            Context context;
            try {
                context=new Context(request,player,mapId,memoryReservation,prepare,accepted.permit());
                context.state.advance(PhotoJobState.Stage.REQUESTED,PhotoJobState.Stage.ADMITTED);jobs.put(request,context);
            }catch(Throwable error){accepted.permit().close();throw error;}
            try {
                prepare.wakeup(()->{if(!offer(context))context.finishFailure(new RejectedExecutionException("唤醒队列容量不足"));});
                if(!offer(context))context.finishFailure(new RejectedExecutionException("放行队列容量不足"));
            } catch(Throwable error) {
                context.finishFailure(error);
                if(error instanceof VirtualMachineError fatal)throw fatal;
            }
            return context.handle;
        }
    }

    private boolean offer(Context context) {
        synchronized(context.scheduling) {
            // 准备器的迟到wakeup只唤醒父控制工作，不能在render阶段插入第三个token。
            if(context.state.state().stage()==PhotoJobState.Stage.RENDERING && context.lanesActivated)return true;
            return offer(context.control);
        }
    }
    private boolean offer(WorkToken token) {
        Context context=token.context;
        synchronized(context.scheduling) {
            token.needsDispatch=true;
            if(token.running || token.queued || token.done || !context.valid())return true;
            boolean accepted=queue.offer(new LeakyWorkQueue.Work<>(context.player,token.id,token,limits.quantumCpuNanos));
            if(accepted){token.queued=true;token.needsDispatch=false;}return accepted;
        }
    }
    /** 必须在该批次CPU/队列已结算、owner仍持有资源时调用。 */
    private void exitOwner(WorkToken token,boolean again) {
        Context context=token.context;
        synchronized(context.scheduling) {
            try{if(token.lane<0 && context.outputOnly && context.memory!=null)context.memory.outputOnly();}catch(Throwable error){context.finishFailure(error);}
            token.running=false;context.running.decrementAndGet();
            try{if(context.valid()) {
                if(token.lane<0 && context.state.state().stage()==PhotoJobState.Stage.RENDERING) {
                    context.lanesActivated=true;context.renderStarted=System.nanoTime();
                    for(var lane:context.lanes)if(!offer(lane)){context.finishFailure(new RejectedExecutionException("渲染分片队列已满"));break;}
                } else if(token.lane>=0) {
                    if(!token.done && !offer(token))context.finishFailure(new RejectedExecutionException("渲染分片无法重新入队"));
                    if(context.valid() && context.running.get()==0 && Arrays.stream(context.lanes).allMatch(lane->lane.done && !lane.queued && !lane.running)) {
                        context.renderFinished=System.nanoTime();context.state.advance(PhotoJobState.Stage.RENDERING,PhotoJobState.Stage.ENCODING);
                        if(!offer(context.control))context.finishFailure(new RejectedExecutionException("编码队列已满"));
                    }
                } else if((again || token.needsDispatch) && !offer(token))context.finishFailure(new RejectedExecutionException("控制工作无法重新入队"));
            }}catch(Throwable error){context.finishFailure(error);}
        }
        context.cleanup();
    }

    private void tick() {
        long wall = System.nanoTime(), cpu = cpuTime ? meter.getCurrentThreadCpuTime() : -1;
        try {
            dispatchTick();
        } finally {
            long end = cpuTime ? meter.getCurrentThreadCpuTime() : -1;
            budget.chargeMaintenance(cpu >= 0 && end >= cpu ? end - cpu : Math.max(0, System.nanoTime() - wall));
        }
    }

    private void dispatchTick() {
        if (closed) return;
        for(Context context:jobs.values()) {
            TimeoutException timeout=null;
            synchronized(context.scheduling) {
                if(!context.state.state().stage().terminal() && System.nanoTime()-context.deadline>=0 && !context.settling) {
                    timeout=new TimeoutException("照片任务超时："+context.diagnostics());context.failure=timeout;context.state.expire();
                }
            }
            if(timeout!=null)context.finishFailure(timeout);
        }
        if (!slots.tryAcquire()) return;
        Optional<CpuBudget.Reservation> reservation = budget.reserve(limits.quantumCpuNanos);
        if (reservation.isEmpty()) {
            slots.release();
            return;
        }
        Optional<LeakyWorkQueue.Work<WorkToken>> next;
        try{next=queue.poll(System.nanoTime());}catch(Throwable error){reservation.get().close();slots.release();throw error;}
        if(next.isEmpty()){reservation.get().close();slots.release();return;}
        WorkToken token=next.get().value();Context context=token.context;
        synchronized(context.scheduling) {
            token.queued=false;token.needsDispatch=false;
            if(!context.valid()){queue.account(next.get(),0);reservation.get().close();slots.release();return;}
            token.running=true;context.running.incrementAndGet();
        }
        Batch batch=null;
        try{batch=new Batch(next.get(),reservation.get());workers.execute(batch);}
        catch(Throwable error) {
            if(batch!=null)batch.discard(error);
            else {try{reservation.get().close();}finally{try{queue.account(next.get(),0);}finally{context.finishFailure(error);try{exitOwner(token,false);}finally{slots.release();}}}}
            if(error instanceof VirtualMachineError fatal)throw fatal;
        }
    }

    private final class Batch implements Runnable {
        final LeakyWorkQueue.Work<WorkToken> work;final CpuBudget.Reservation reservation;
        final AtomicBoolean started=new AtomicBoolean();
        Batch(LeakyWorkQueue.Work<WorkToken> work,CpuBudget.Reservation reservation){this.work=work;this.reservation=reservation;}
        void discard(Throwable cause) {
            if(!started.compareAndSet(false,true))return;
            Context context=work.value().context;
            try{reservation.close();}finally{try{queue.account(work,0);}finally{context.finishFailure(cause);try{exitOwner(work.value(),false);}finally{slots.release();}}}
        }
        @Override public void run() {
            if(!started.compareAndSet(false,true))return;
            WorkToken token=work.value();Context context=token.context;
            long wallStart=System.nanoTime(),start=cpuTime?meter.getCurrentThreadCpuTime():-1;
            boolean again=false,preparing=false,rendering=token.lane>=0,renderEntered=false;
            try {
                if(context.valid()) {
                    boolean scanBatch=token.lane<0 && context.preparation!=null && context.preparation.scanning();
                    long slice=scanBatch?limits.scanBatchCpuNanos:limits.maxBatchCpuNanos;
                    long deadline=wallStart+slice;
                    if(rendering) {
                        renderEntered=true;synchronized(context.scheduling){context.activeRenderLanes++;context.peakRenderLanes=Math.max(context.peakRenderLanes,context.activeRenderLanes);}
                        final boolean[] entered={false};
                        var rendered=token.renderer.renderBatch(token.cursor,batchPixels,deadline,()->{if(!entered[0]){entered[0]=true;probe.entered(context.id,token.lane);}return System.nanoTime();},()->!context.valid());
                        if(rendered.status()==CpuPhotoRenderer.Status.CANCELLED)context.finishFailure(new CancellationException("渲染分片已取消"));
                        else if(rendered.status()==CpuPhotoRenderer.Status.DONE)token.done=true;
                    } else {
                        do {preparing|=context.state.state().stage()==PhotoJobState.Stage.CAPTURING;again=step(context,deadline);}
                        while(again && context.state.state().stage()==PhotoJobState.Stage.CAPTURING && context.preparation!=null && context.preparation.scanning()==scanBatch && context.valid() && System.nanoTime()<deadline);
                    }
                } else context.finishFailure(new CancellationException("照片已失效"));
            } catch(Throwable error){context.finishFailure(error);if(error instanceof VirtualMachineError fatal)throw fatal;}
            finally {
                if(renderEntered)synchronized(context.scheduling){context.activeRenderLanes--;}
                long measured=cpuTime?meter.getCurrentThreadCpuTime():-1;
                long cost=start>=0 && measured>=start?measured-start:Math.max(0,System.nanoTime()-wallStart);
                if(start<0 || measured<start)context.usedWall=true;
                context.cpuNanos.addAndGet(cost);if(preparing)context.preparationCpu.addAndGet(cost);if(rendering)context.renderCpu.addAndGet(cost);
                try{reservation.settle(cost);}catch(Throwable error){context.finishFailure(error);}
                finally{try{queue.account(work,cost);}catch(Throwable error){context.finishFailure(error);}
                    finally{try{exitOwner(token,again);}finally{slots.release();}}}
            }
        }
    }

    private boolean step(Context context,long deadline) throws Exception {
        switch (context.state.state().stage()) {
            case ADMITTED -> {
                context.captureStarted=System.nanoTime();context.state.advance(PhotoJobState.Stage.ADMITTED,PhotoJobState.Stage.CAPTURING);return true;
            }
            case CAPTURING -> {
                Preparation work=context.preparation;
                PreparationStep progress;
                try{progress=work.step(batchPixels,deadline);}
                finally{context.progress=work.progress();}
                if(progress==PreparationStep.WAITING)return false;
                if(progress==PreparationStep.CONTINUE)return true;
                context.prepared=Objects.requireNonNull(work.result());context.memory=context.prepared.memory;context.assetFingerprint=context.prepared.scene.assets().fingerprint();
                context.captureFinished=System.nanoTime();context.preparation=null;work.close();
                context.state.advance(PhotoJobState.Stage.CAPTURING,PhotoJobState.Stage.PREPARING);return true;
            }
            case PREPARING -> {
                long required=Math.addExact(4*1024*1024,context.prepared.scene.estimatedBytes());
                int count=Math.min(2,limits.workers);long buffers=Math.multiplyExact(count,8L*1024*1024);
                if(context.prepared.memory!=null)context.prepared.memory.claim(buffers);
                required=Math.addExact(required,buffers);
                if(context.prepared.memory==null && context.reservedBytes<required && !context.permit.grow(required-context.reservedBytes))throw new RejectedExecutionException("渲染分片与输出工作集预约不足");
                WorkToken[] lanes=new WorkToken[count];
                for(int lane=0;lane<count;lane++) {
                    var work=new WorkToken(context,lane);work.renderer=rendererFactory.create(context.prepared.scene,context.prepared.camera,context.prepared.settings);work.cursor=work.renderer.cursor(lane,count);lanes[lane]=work;
                }
                context.lanes=lanes;
                context.state.advance(PhotoJobState.Stage.PREPARING,PhotoJobState.Stage.READY);
                context.state.advance(PhotoJobState.Stage.READY,PhotoJobState.Stage.RENDERING);return false;
            }
            case RENDERING -> {return false;}
            case ENCODING -> {
                if(context.mapId<0)return false;
                int[] rgb=new int[16384];WorkToken[] lanes=context.lanes;
                for(int lane=0;lane<lanes.length;lane++){int[] pixels=lanes[lane].cursor.copyRgb();for(int i=0;i<pixels.length;i++)rgb[i*lanes.length+lane]=pixels[i];}
                Map<String,String> metadata=new HashMap<>(context.prepared.sourceMetadata);
                if(context.memory!=null){metadata.put("workspacePeak",Long.toString(context.memory.peak()));metadata.put("workspaceUsedAtEncode",Long.toString(context.memory.live()));}
                metadata.put("renderWallNanos",Long.toString(context.renderFinished-context.renderStarted));metadata.put("renderCpuNanos",Long.toString(context.renderCpu.get()));metadata.put("renderLaneCount",Integer.toString(lanes.length));metadata.put("peakRenderLanes",Integer.toString(context.peakRenderLanes));
                metadata.put("captureWallNanos",Long.toString(context.captureFinished-context.captureStarted));
                metadata.put("preparationCpuNanos",Long.toString(context.preparationCpu.get()));
                metadata.putAll(Map.of("engine","textured-v2","request",context.id.toString(),"assets",context.assetFingerprint,
                        "palette",context.prepared.palette.version(),"quantizer","linear-euclidean-v1","mode","publisher-port"));
                ImmutablePhoto photo=new ImmutablePhoto(rgb,context.prepared.palette.quantize(rgb),metadata);
                Prepared owned=context.prepared;context.prepared=null;owned.lease.close();context.outputOnly=true;
                context.lanes=null;
                var token = context.state.beginCommit().orElseThrow(() -> new CancellationException("照片已取消"));
                DiskPhotoStore.Stored stored = store.commit(context.mapId, photo);
                if (!context.state.stored(token)) return false;
                context.storedResult = stored;
                publisher.publish(stored, photo, context.gate).whenComplete((receipt, error) -> {
                    if (error != null) context.settlementFailed(error);
                    else if (context.receiptCurrent() && context.state.installed(token) && context.state.succeed(token)) { /* 最后一个计算owner退出后才完成Future。 */ } else
                        context.finishFailure(new CancellationException("提交回调已失效"));
                    context.cleanup();
                });
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    public int pixelLimit(int requested) {
        if(requested<=0 || requested>16384)throw new IllegalArgumentException("V2每批像素上限必须在1–16384内");
        batchPixels=Math.min(requested,limits.batchPixels);return batchPixels;
    }
    public AdmissionController.Snapshot admissionSnapshot() {
        return admission.snapshot();
    }

    public CpuBudget.Snapshot cpuSnapshot() {
        return budget.snapshot();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        admission.stopAdmission();
        ticker.shutdownNow();
        for (Runnable task : workers.shutdownNow())
            if (task instanceof CpuPhotoPipeline.Batch batch) batch.discard(new CancellationException("管线关闭"));
        for (Context context : jobs.values()) context.finishFailure(new CancellationException("管线关闭"));
    }
}
