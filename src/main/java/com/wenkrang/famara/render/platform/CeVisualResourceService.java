package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.asset.*;
import com.wenkrang.famara.render.scene.VisualStateTable;
import com.wenkrang.famara.render.schedule.*;
import java.lang.management.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Global有界冻结与后台资产准备重叠；转换分片，且同代两部分都成功后才发布。 */
public final class CeVisualResourceService implements AutoCloseable {
    public record Limits(int maximumStates,long maximumFrozenBytes,int entriesPerTick,long nanosPerTick,long quantumCpuNanos) {
        public Limits {if(maximumStates<=0 || maximumFrozenBytes<=0 || entriesPerTick<=0 || nanosPerTick<=0 || quantumCpuNanos<=0)throw new IllegalArgumentException("CE视觉冻结限额无效");}
    }
    public record Ready(long generation,long epoch,VisualStateTable table) {}
    public record Input(AssetRepository.Lease lease,VisualStateTable table,AutoCloseable tableMemory) implements AutoCloseable {
        public Input(AssetRepository.Lease lease,VisualStateTable table){this(lease,table,()->{});}
        public void close(){lease.close();try{tableMemory.close();}catch(Exception error){throw new IllegalStateException(error);}}
    }
    private static final class Build {
        final long epoch,start=System.nanoTime();
        final CompletableFuture<Ready> result;
        CeCompat.VisualCursor cursor;
        FrozenVisualBuffer copying=new FrozenVisualBuffer();
        VisualStateTable.Builder converting;
        long freezeNanos,frozenPeak,tablePeak;
        int frozenRows;
        volatile int tableSlices;
        // 已排队也持有owner：取消不能提前归还lambda仍能访问的buffer/builder预约。
        boolean tableScheduled;
        volatile boolean cancelled;
        VisualStateTable table;
        BudgetedAssetLoader.Staged staged;
        RenderMemoryBudget.Lease memory;
        Build(long epoch,CompletableFuture<Ready> result){this.epoch=epoch;this.result=result;}
    }
    private RenderMemoryBudget global;
    private SharedMemory publishedMemory;
    public CeVisualResourceService memory(RenderMemoryBudget global){this.global=global;return this;}
    private final AssetRepository repository;
    private final BudgetedAssetLoader loader;
    private final BudgetedWorkRunner background;
    private final CpuBudget budget;
    private final Limits limits;
    private Path base;
    private String baseSha;
    private List<BudgetedAssetLoader.Source> supplementalBase=List.of();
    public synchronized void base(Path path,String sha256){if(closed || timer!=null || building!=null)throw new IllegalStateException("视觉服务已启动或关闭");base=Objects.requireNonNull(path);baseSha=sha256;}
    /** Lower-priority versioned vanilla models/texture fallbacks used when a CE pack inherits game parents absent from its selected base version. */
    public synchronized void supplementalBase(Path path,String sha256){
        if(closed || timer!=null || building!=null)throw new IllegalStateException("视觉服务已启动或关闭");
        Objects.requireNonNull(path);if(sha256==null || !sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("补充基础资源缺少有效SHA256");
        if(supplementalBase.size()>=4)throw new IllegalArgumentException("补充基础资源超过四个来源");
        if(supplementalBase.stream().anyMatch(s->s.path().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())))return;
        var next=new ArrayList<>(supplementalBase);next.add(new BudgetedAssetLoader.Source(path,ResourcePack.Role.BASE,true,sha256));supplementalBase=List.copyOf(next);
    }
    private final Consumer<Throwable> errors;
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private Sched.Task timer;
    private long observed=Long.MIN_VALUE;
    private volatile Build building;
    private volatile Throwable failure;
    private volatile Ready ready;
    private volatile CompletableFuture<Ready> readiness=new CompletableFuture<>();
    private volatile boolean closed;
    public CeVisualResourceService(Path base,AssetLimits assets,Limits limits,AssetRepository repository,BudgetedAssetLoader loader,
                                   BudgetedWorkRunner background,CpuBudget budget,Consumer<Throwable> errors) {
        this.base=Objects.requireNonNull(base);Objects.requireNonNull(assets);this.limits=Objects.requireNonNull(limits);
        this.repository=Objects.requireNonNull(repository);this.loader=Objects.requireNonNull(loader);this.background=Objects.requireNonNull(background);
        this.budget=Objects.requireNonNull(budget);this.errors=Objects.requireNonNull(errors);budget.retryAfterNanos(limits.quantumCpuNanos);
    }
    public synchronized void start(){if(closed || timer!=null)throw new IllegalStateException("CE视觉服务已启动或关闭");timer=Sched.globalTimer(this::tick,1,1);}
    public synchronized void tick() {
        if(closed)return;long epoch=CeCompat.visualEpoch();
        if(epoch!=observed) {
            cancelBuild("CE视觉代已更新");
            if(observed!=Long.MIN_VALUE)readiness=new CompletableFuture<>();
            observed=epoch;failure=null;ready=null;if(publishedMemory!=null)publishedMemory.close();publishedMemory=null;
        }
        if(ready!=null || failure!=null)return;
        var allowed=budget.reserve(limits.quantumCpuNanos);if(allowed.isEmpty())return;
        long wall=System.nanoTime(),start=cpu();Build job=building;
        try {
            if(job==null) {
                job=new Build(epoch,readiness);building=job;if(global!=null)job.memory=global.reserve("visual-table-build",1048576);var sources=CeCompat.visualSources();
                if(sources.epoch()!=epoch)throw new CancellationException("CE来源在冻结前变化");
                if(sources.cePresent() && !sources.adapterAvailable())throw new IllegalStateException("CE存在但视觉适配不可用，拒绝按原版载体拍摄");
                job.cursor=CeCompat.openVisualCursor(epoch);
                List<BudgetedAssetLoader.Source> paths=new ArrayList<>();
                for(Path path:sources.paths())paths.add(new BudgetedAssetLoader.Source(path,ResourcePack.Role.CUSTOM,false));
                paths.add(new BudgetedAssetLoader.Source(base,ResourcePack.Role.BASE,true,baseSha));Build captured=job;
                paths.addAll(supplementalBase);
                loader.prepareSources(paths,()->current(captured)).whenComplete((staged,error)->{
                    synchronized(CeVisualResourceService.this) {
                        if(!current(captured)){if(staged!=null)staged.generation().releaseMemory();return;}
                        if(error!=null)failed(captured,error);else{captured.staged=staged;publish(captured);}
                    }
                });
                if(!current(job))return;
            }
            if(job.cursor==null)return;
            long deadline=wall+limits.nanosPerTick;Build captured=job;
            for(int n=0;n<limits.entriesPerTick && System.nanoTime()<deadline && !job.cursor.done();n++) {
                List<CeCompat.VisualState> batch=job.cursor.next(1);if(batch.size()>1)throw new IllegalStateException("CE视觉游标违反批次上限");
                for(var state:batch)job.copying.add(state,limits.maximumStates,limits.maximumFrozenBytes,bytes->resize(captured,bytes));
            }
            if(job.cursor.done()) {
                job.freezeNanos=System.nanoTime()-job.start;job.cursor=null;job.copying.seal();job.frozenRows=job.copying.count();job.frozenPeak=job.copying.peak();scheduleTable(job);
            }
        }catch(Throwable error){if(job!=null)failed(job,error);if(error instanceof VirtualMachineError fatal)throw fatal;}
        finally {long end=cpu();allowed.get().settle(start>=0 && end>=start?end-start:Math.max(0,System.nanoTime()-wall));}
    }
    private void resize(Build job,long bytes){if(job.memory!=null)job.memory.resize(Math.max(1048576,bytes));}
    private boolean tableStep(Build job) {
        if(!current(job))throw new CancellationException("CE视觉输入已过期");
        if(job.converting==null)job.converting=new VisualStateTable.Builder(job.epoch,limits.maximumStates,limits.maximumFrozenBytes,working->resize(job,Math.addExact(job.copying.bytes(),working)));
        long deadline=System.nanoTime()+limits.nanosPerTick;
        for(int n=0;n<limits.entriesPerTick && System.nanoTime()<deadline && !job.copying.isEmpty();n++) {
            if(!current(job))throw new CancellationException("CE视觉输入已过期");
            var state=job.copying.remove();job.converting.add(state.serverState(),state.material(),state.id(),state.vanillaState(),state.visualState(),state.customBlock());
        }
        job.tableSlices++;return job.copying.isEmpty();
    }
    private void scheduleTable(Build job) {
        if(!current(job))return;job.tableScheduled=true;
        try {
            background.submit(()->tableStep(job)).whenComplete((done,error)->{
                synchronized(CeVisualResourceService.this) {
                    job.tableScheduled=false;
                    if(!current(job)){dispose(job);return;}
                    if(error!=null){failed(job,error);return;}
                    try {
                        if(Boolean.TRUE.equals(done)) {
                            // supplier已退出，raw/解析缓存退出后才缩减到保守常驻预约。
                            job.tablePeak=job.converting.workingPeakBytes();job.table=job.converting.finish();job.converting=null;job.copying=null;
                            if(job.memory!=null)job.memory.resize(job.table.estimatedRetainedBytes());publish(job);
                        } else scheduleTable(job);
                    } catch(Throwable failure){failed(job,failure);if(failure instanceof VirtualMachineError fatal)throw fatal;}
                }
            });
        } catch(Throwable failure) {
            job.tableScheduled=false;failed(job,failure);if(failure instanceof VirtualMachineError fatal)throw fatal;
        }
    }
    private boolean current(Build job){return !closed && building==job && !job.cancelled && job.epoch==CeCompat.visualEpoch();}
    private void publish(Build job) {
        if(!current(job) || job.table==null || job.staged==null)return;
        var metrics=job.staged.metrics();
        if(!repository.publish(metrics.generation(),job.staged.generation())){failed(job,new IllegalStateException("资源代已过期或缓存容量不足"));return;}
        if(job.memory!=null){job.memory.transfer("visual-table-"+job.epoch);publishedMemory=new SharedMemory(job.memory);job.memory=null;}
        Ready published=new Ready(metrics.generation(),job.epoch,job.table);ready=published;job.staged=null;job.table=null;
        java.util.logging.Logger.getLogger("FamaraV2").info("V2视觉资源就绪：资源代="+metrics.generation()+"，CE视觉映射条目="+(published.table.size()-published.table.vanillaRemapCount())+"，原生槽位条目="+published.table.nativeSlotEntries()+"，原版客户端状态重映射="+published.table.vanillaRemapCount()
                +"；冻结条目="+job.frozenRows+"/"+limits.maximumStates+"，冻结峰值="+job.frozenPeak+"/"+limits.maximumFrozenBytes+"，转换工作峰值="+job.tablePeak+"，常驻预约="+published.table.estimatedRetainedBytes()+"，转换批次="+job.tableSlices
                +"；冻结wall="+job.freezeNanos/1e6+"ms，资产wall="+metrics.wallNanos()/1e6+"ms，资产CPU="+metrics.cpuNanos()/1e6+"ms，批次="+metrics.slices()+"，并行总wall="+(System.nanoTime()-job.start)/1e6+"ms");
        job.result.complete(published);
    }
    private void dispose(Build job) {
        if(job.tableScheduled)return;
        job.cursor=null;job.copying=null;job.converting=null;job.table=null;
        if(job.memory!=null)job.memory.close();job.memory=null;
    }
    private void failed(Build job,Throwable error) {
        if(!current(job))return;job.cancelled=true;job.cursor=null;
        if(job.staged!=null)job.staged.generation().releaseMemory();job.staged=null;dispose(job);
        failure=error;ready=null;job.result.completeExceptionally(error);errors.accept(error);
    }
    private void cancelBuild(String reason) {
        Build previous=building;if(previous==null)return;previous.cancelled=true;previous.cursor=null;
        if(previous.staged!=null)previous.staged.generation().releaseMemory();previous.staged=null;dispose(previous);building=null;
        previous.result.completeExceptionally(new CancellationException(reason));
    }
    public synchronized String status() {
        Throwable error=failure;if(error!=null){while((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null)error=error.getCause();String detail=error.toString();return "资源准备失败："+detail.substring(0,Math.min(400,detail.length()));}
        if(ready().isPresent())return "READY";
        Build job=building;if(job==null || observed!=CeCompat.visualEpoch())return "等待CE视觉代更新";
        return job.cursor!=null?"准备中（CE冻结与资源复制/编译并行；冻结条目="+job.copying.count()+"/"+limits.maximumStates+"，冻结字节="+job.copying.bytes()+"/"+limits.maximumFrozenBytes+"）":"准备中（CE分片转换/等待资产；冻结条目="+job.frozenRows+"，转换已完成批次="+job.tableSlices+"）";
    }
    private long cpu(){return meter.isCurrentThreadCpuTimeSupported() && meter.isThreadCpuTimeEnabled()?meter.getCurrentThreadCpuTime():-1;}
    public CompletionStage<Ready> readiness(){return readiness.minimalCompletionStage();}
    public Optional<Ready> ready(){Ready value=ready;return value!=null && !closed && value.epoch==CeCompat.visualEpoch()?Optional.of(value):Optional.empty();}
    public synchronized Input acquire() {
        Ready value=ready().orElseThrow(()->new IllegalStateException("视觉资源尚未准备好"));var lease=repository.acquire();
        if(lease.generation().id()!=value.generation || value.epoch!=CeCompat.visualEpoch() || closed){lease.close();throw new IllegalStateException("视觉资源正在切换");}return new Input(lease,value.table,publishedMemory==null?()->{}:publishedMemory.retain());
    }
    @Override public synchronized void close(){closed=true;ready=null;if(publishedMemory!=null)publishedMemory.close();publishedMemory=null;if(timer!=null)timer.cancel();cancelBuild("CE视觉服务关闭");readiness.completeExceptionally(new CancellationException("CE视觉服务关闭"));}
}
