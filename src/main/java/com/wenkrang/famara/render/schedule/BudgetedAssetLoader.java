package com.wenkrang.famara.render.schedule;

import com.wenkrang.famara.render.asset.*;
import java.lang.management.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 读取、校验、模型编译均按批次记入共享CPU账本；过期构建不覆盖旧代。 */
public final class BudgetedAssetLoader implements AutoCloseable {
    public record Source(Path path,ResourcePack.Role role,boolean required,String sha256) {
        public Source(Path path,ResourcePack.Role role,boolean required){this(path,role,required,null);}
        public Source {Objects.requireNonNull(path);Objects.requireNonNull(role);if(sha256!=null && !sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("基础源SHA无效");}
    }
    public record Limits(int operationsPerStep,long dispatchNanos,long quantumCpuNanos,long maxStepNanos,long timeoutNanos,Long maximumSourceBytes) {
        public Limits(int operationsPerStep,long dispatchNanos,long quantumCpuNanos,long maxStepNanos,long timeoutNanos){this(operationsPerStep,dispatchNanos,quantumCpuNanos,maxStepNanos,timeoutNanos,null);}
        public Limits {
            maximumSourceBytes=maximumSourceBytes==null?1073741824L:maximumSourceBytes;if(maximumSourceBytes<=0 || maximumSourceBytes>17179869184L)throw new IllegalArgumentException("磁盘来源包上限必须有界，且不代表内存额度");
            if(operationsPerStep<=0 || operationsPerStep>4096 || dispatchNanos<1_000_000 || quantumCpuNanos<=0
                    || maxStepNanos<quantumCpuNanos || timeoutNanos<=0)throw new IllegalArgumentException("资产调度限额无效");
        }
    }
    public record Result(long generation,long cpuNanos,long wallNanos,int slices,long estimatedBytes) {}
    public record Staged(Result metrics,AssetGeneration generation) {}
    private final class Build {
        final long ticket,start=System.nanoTime();
        final List<Source> paths;
        final BooleanSupplier inputsCurrent;
        final CompletableFuture<Staged> result=new CompletableFuture<>();
        final boolean publish;
        final List<ResourcePack> layers=new ArrayList<>();
        int source,slices;
        long cpu;
        RenderMemoryBudget.Lease memory;
        BlockResourceCursor reading;
        AssetGeneration.BuildCursor compiling;
        Build(List<Source> paths,BooleanSupplier inputsCurrent,boolean publish) {
            this.publish=publish;
            this.ticket=repository.requestBuild();this.paths=List.copyOf(paths);this.inputsCurrent=inputsCurrent;
        }
        void dispose() {
            try { if(reading!=null)reading.close(); }
            catch(Exception e) { result.completeExceptionally(e); }
            if(compiling!=null)compiling.close();
            reading=null;compiling=null;layers.clear();if(memory!=null)memory.close();memory=null;
        }
    }
    private final RenderMemoryBudget global;
    private final AssetRepository repository;
    private final AssetLimits assets;
    private final Limits limits;
    private final CpuBudget cpu;
    private final ScheduledThreadPoolExecutor executor;
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private final boolean cpuSupported;
    private Build requested,active;
    private ScheduledFuture<?> scheduled;
    private volatile boolean closed;
    private long scheduleVersion;
    public BudgetedAssetLoader(AssetRepository repository,AssetLimits assets,Limits limits,CpuBudget cpu){this(repository,assets,limits,cpu,null);}
    public BudgetedAssetLoader(AssetRepository repository,AssetLimits assets,Limits limits,CpuBudget cpu,RenderMemoryBudget global) {
        this.global=global;this.repository=Objects.requireNonNull(repository);this.assets=Objects.requireNonNull(assets);
        this.limits=Objects.requireNonNull(limits);this.cpu=Objects.requireNonNull(cpu);
        cpu.retryAfterNanos(limits.quantumCpuNanos);
        boolean enabled=meter.isCurrentThreadCpuTimeSupported();
        if(enabled)try { if(!meter.isThreadCpuTimeEnabled())meter.setThreadCpuTimeEnabled(true); }
        catch(SecurityException | UnsupportedOperationException e) { enabled=false; }
        cpuSupported=enabled;
        executor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Famara-v2-assets");t.setDaemon(true);return t;});
        executor.setRemoveOnCancelPolicy(true);
    }
    public CompletionStage<Result> reload(List<Path> highToLow,BooleanSupplier inputsCurrent) {
        return reloadSources(highToLow.stream().map(p->new Source(p,ResourcePack.Role.UNKNOWN,true)).toList(),inputsCurrent);
    }
    public CompletionStage<Result> reloadSources(List<Source> highToLow,BooleanSupplier inputsCurrent) {
        return build(highToLow,inputsCurrent,true).thenApply(Staged::metrics);
    }
    public CompletionStage<Staged> prepareSources(List<Source> highToLow,BooleanSupplier inputsCurrent) {return build(highToLow,inputsCurrent,false);}
    private CompletionStage<Staged> build(List<Source> highToLow,BooleanSupplier inputsCurrent,boolean publish) {
        Build next,obsolete;
        synchronized(this) {
            if(closed)throw new IllegalStateException("资产服务已关闭");
            if(highToLow.isEmpty())throw new IllegalArgumentException("没有资源来源");
            next=new Build(highToLow,Objects.requireNonNull(inputsCurrent),publish);
            obsolete=requested!=active?requested:null;requested=next;
            if(scheduled!=null)scheduled.cancel(false);scheduled=null;schedule(0);
        }
        // 完成回调可能进入平台服务锁，绝不能在loader锁内调用。
        if(obsolete!=null)obsolete.result.completeExceptionally(new CancellationException("资源请求已被更新"));
        return next.result.minimalCompletionStage();
    }
    private synchronized void schedule(long delay) {
        if(closed || scheduled!=null && !scheduled.isDone())return;
        long version=++scheduleVersion;
        scheduled=executor.schedule(()->runStep(version),delay,TimeUnit.NANOSECONDS);
    }
    private void runStep(long version) {
        Build job,obsolete=null;
        synchronized(this) {
            if(version!=scheduleVersion)return;
            scheduled=null;
            if(closed)return;
            if(active!=requested){obsolete=active;active=requested;}
            job=active;
        }
        if(obsolete!=null){obsolete.result.completeExceptionally(new CancellationException("资源请求已被更新"));obsolete.dispose();}
        if(job==null || job.result.isDone())return;
        try {
            if(!job.inputsCurrent.getAsBoolean() || System.nanoTime()-job.start>=limits.timeoutNanos) {
                job.result.completeExceptionally(new IllegalStateException("资源输入已失效或构建超时"));job.dispose();return;
            }
        } catch(RuntimeException e) { job.result.completeExceptionally(e);job.dispose();return; }
        Optional<CpuBudget.Reservation> reservation=cpu.reserve(limits.quantumCpuNanos);
        if(reservation.isEmpty()) {
            long wait=cpu.retryAfterNanos(limits.quantumCpuNanos);
            schedule(Math.max(limits.dispatchNanos,Math.min(wait,1_000_000_000L)));return;
        }
        long start=System.nanoTime(),cpuStart=cpuSupported?meter.getCurrentThreadCpuTime():-1;
        AssetGeneration finished=null;
        try {
            if(global!=null && job.memory==null)job.memory=global.reserve("asset-build",Math.addExact(assets.maxTotalBytes(),4L*1024*1024));
            long deadline=start+limits.maxStepNanos;
            if(job.compiling==null) {
                if(job.reading==null)job.reading=new BlockResourceCursor(job.paths.stream().map(p->new BlockResourceCursor.Input(p.path,p.role,p.required,p.sha256)).toList(),assets,limits.maximumSourceBytes);
                if(job.reading.step(limits.operationsPerStep,deadline,System::nanoTime)) {
                    ResourcePack pack=job.reading.result();var selected=job.reading.metrics();job.reading.close();job.reading=null;
                    java.util.logging.Logger.getLogger("FamaraV2").info("V2方块依赖资源已读取：扫描条目="+selected.indexedEntries()+"，保留路径="+selected.retainedPaths()+"，索引字节="+selected.indexedBytes()+"，索引峰值="+selected.indexPeakBytes()+"，选中条目="+selected.selectedEntries()+"，选中字节="+selected.selectedBytes()+"，磁盘来源上限="+limits.maximumSourceBytes);
                    job.compiling=AssetGeneration.builder(job.ticket,pack,assets);
                }
            } else if(job.compiling.step(limits.operationsPerStep,deadline,System::nanoTime))finished=job.compiling.result();
        } catch(Throwable e) {
            job.result.completeExceptionally(e);job.dispose();
            if(e instanceof VirtualMachineError fatal)throw fatal;
        } finally {
            long end=cpuSupported?meter.getCurrentThreadCpuTime():-1;
            long cost=cpuStart>=0 && end>=cpuStart?end-cpuStart:Math.max(0,System.nanoTime()-start);
            reservation.get().settle(cost);job.cpu+=cost;job.slices++;
        }
        if(finished!=null) {
            try {
                boolean current=job.inputsCurrent.getAsBoolean();
                if(job.memory!=null){job.memory.transfer("asset-generation-"+job.ticket);finished.memory(job.memory);job.memory=null;}
                boolean accepted;
                synchronized(this){accepted=!closed && requested==job && current && (!job.publish || repository.publish(job.ticket,finished));}
                if(accepted) {
                    job.result.complete(new Staged(new Result(job.ticket,job.cpu,System.nanoTime()-job.start,job.slices,finished.estimatedRetainedBytes()),finished));
                } else {finished.releaseMemory();job.result.completeExceptionally(new CancellationException("资源代已过期或缓存容量不足"));}
            } catch(Throwable error) {
                if(!job.result.isDone())finished.releaseMemory();job.result.completeExceptionally(error);
                if(error instanceof VirtualMachineError fatal)throw fatal;
            } finally { job.dispose(); }
        }
        if(job.result.isDone())synchronized(this){if(active==job)active=null;if(requested==job)requested=null;}
        if(closed){disposeAll();return;}
        synchronized(this){if(requested!=null && requested!=job || !job.result.isDone())schedule(limits.dispatchNanos);}
    }
    private void disposeAll() {
        Build previous,pending;
        synchronized(this){previous=active;pending=requested;active=null;requested=null;}
        if(previous!=null){previous.result.completeExceptionally(new CancellationException("资产服务关闭"));previous.dispose();}
        if(pending!=null && pending!=previous){pending.result.completeExceptionally(new CancellationException("资产服务关闭"));pending.dispose();}
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        if(scheduled!=null)scheduled.cancel(false);
        executor.execute(this::disposeAll);executor.shutdown();
    }
}
