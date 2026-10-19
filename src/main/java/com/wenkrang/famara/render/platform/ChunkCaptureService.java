package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.compat.RuntimeCompat;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.render.schedule.CpuBudget;
import org.bukkit.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;


/** 只加载已生成区块；Global限量派发，区域owner中复制快照，不等待Future或生成地形。 */
public final class ChunkCaptureService implements AutoCloseable {
    public record Coordinate(int x,int z) {}
    public record Limits(int maxRequests,int maxChunksPerRequest,int maxInflight,int startsPerTick,
                         long bytesPerChunk,long maximumSnapshotBytes,long nanosPerTick,long quantumCpuNanos,long timeoutNanos,Integer windowChunks) {
        public Limits(int maxRequests,int maxChunksPerRequest,int maxInflight,int startsPerTick,long bytesPerChunk,long maximumSnapshotBytes,long nanosPerTick,long quantumCpuNanos,long timeoutNanos){this(maxRequests,maxChunksPerRequest,maxInflight,startsPerTick,bytesPerChunk,maximumSnapshotBytes,nanosPerTick,quantumCpuNanos,timeoutNanos,null);}
        public Limits {
            windowChunks=windowChunks==null?1:windowChunks;if(windowChunks<1 || windowChunks>2 || windowChunks>maxChunksPerRequest || maximumSnapshotBytes/Math.max(1,bytesPerChunk)<windowChunks)throw new IllegalArgumentException("捕获窗口只允许1或2块且需有快照预约");
            if(maxRequests<=0 || maxChunksPerRequest<=0 || maxInflight<=0 || startsPerTick<=0 || bytesPerChunk<=0
                    || maximumSnapshotBytes<bytesPerChunk || nanosPerTick<=0 || quantumCpuNanos<=0 || timeoutNanos<=0)
                throw new IllegalArgumentException("区块采集限额无效");
        }
    }
    @FunctionalInterface public interface Extras {List<com.wenkrang.famara.render.scene.LegacyEntity> capture(Chunk chunk);}
    public record Captured(ChunkSnapshot snapshot,long startedNanos,long completedNanos,List<com.wenkrang.famara.render.scene.LegacyEntity> entities) {
        public Captured(ChunkSnapshot snapshot,long startedNanos,long completedNanos){this(snapshot,startedNanos,completedNanos,List.of());}
        public Captured{entities=List.copyOf(entities);}
    }
    public record Metrics(int requests,int inflight,long reservedBytes,long captures,long overrunSlices) {}
    private enum Stage { PENDING, LOADING, LOADED, CAPTURING, DONE }
    public record Timings(long dispatchWaitNanos,long loadingNanos,long ownerQueueNanos,long copyWallNanos,long copyCpuNanos,int budgetRetries) {}
    private static final class Piece {
        final Coordinate coordinate;Stage stage=Stage.PENDING;boolean active;
        final long created=System.nanoTime();long started,loaded,ownerQueued,ownerWait,copyWall,copyCpu;int retries;
        Piece(Coordinate coordinate){this.coordinate=coordinate;}
    }
    public final class Request implements AutoCloseable {
        private final World world;
        private final long deadline;
        private long reservation;
        private final com.wenkrang.famara.render.schedule.RenderMemoryBudget.Lease memory;
        private final boolean blocks;
        private Extras extras;
        private final LinkedHashMap<Coordinate,Piece> pieces=new LinkedHashMap<>();
        private final Map<Coordinate,Captured> snapshots=new HashMap<>();
        private final CompletableFuture<Request> future=new CompletableFuture<>();
        private boolean released;
        private int outstanding,activeCaptures;
        private Request(World world,List<Coordinate> coordinates,long reservation,Extras extras,boolean blocks) {
            this.blocks=blocks;this.extras=extras;
            memory=global==null?null:global.reserve("raw-snapshots",reservation);
            this.world=world;this.reservation=reservation;deadline=System.nanoTime()+limits.timeoutNanos;
            for(Coordinate coordinate:coordinates)pieces.put(coordinate,new Piece(coordinate));
        }
        public CompletionStage<Request> result(){return future.minimalCompletionStage();}
        public Map<Coordinate,Captured> snapshots() {
            synchronized(ChunkCaptureService.this) {
                if(released || !future.isDone() || future.isCompletedExceptionally())throw new IllegalStateException("区块快照尚不可用");
                return Map.copyOf(snapshots);
            }
        }
        public long reservedBytes(){synchronized(ChunkCaptureService.this){return reservation;}}
        public void consume(Coordinate coordinate) {
            synchronized(ChunkCaptureService.this) {
                if(released || !future.isDone() || future.isCompletedExceptionally())throw new IllegalStateException("只能消费完整成功请求的冻结数据");
                if(!snapshots.containsKey(coordinate))throw new IllegalStateException("快照不存在或已消费："+coordinate);
                long remaining=reservation-limits.bytesPerChunk;if(memory!=null)memory.resize(remaining);
                snapshots.remove(coordinate);reservation=remaining;reserved-=limits.bytesPerChunk;
            }
        }
        public Timings timings(){synchronized(ChunkCaptureService.this){long dispatch=0,load=0,owner=0,wall=0,cpu=0;int retries=0;for(Piece piece:pieces.values()){if(piece.started!=0)dispatch+=piece.started-piece.created;if(piece.loaded!=0 && piece.started!=0)load+=piece.loaded-piece.started;owner+=piece.ownerWait;wall+=piece.copyWall;cpu+=piece.copyCpu;retries+=piece.retries;}return new Timings(dispatch,load,owner,wall,cpu,retries);}}
        public String progress(){synchronized(ChunkCaptureService.this){return pieces.values().stream().filter(p->p.stage!=Stage.DONE).map(p->p.coordinate+":"+p.stage).findFirst().orElse("全部已捕获，等待转换");}}
        @Override public void close(){release(this,new CancellationException("区块快照已释放"));}
    }
    private final com.wenkrang.famara.render.schedule.RenderMemoryBudget global;
    private final Limits limits;
    private final CpuBudget budget;
    private final LinkedHashSet<Request> requests=new LinkedHashSet<>();
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private Sched.Task timer;
    private boolean closed;
    private int inflight;
    private long reserved,captures,overruns;
    private final ThreadLocal<Long> inlineCaptureCpu=new ThreadLocal<>();
    public ChunkCaptureService(Limits limits,CpuBudget budget){this(limits,budget,null);}
    public ChunkCaptureService(Limits limits,CpuBudget budget,com.wenkrang.famara.render.schedule.RenderMemoryBudget global) {
        this.global=global;this.limits=Objects.requireNonNull(limits);this.budget=Objects.requireNonNull(budget);budget.retryAfterNanos(limits.quantumCpuNanos);
    }
    public synchronized void start() {
        if(closed || timer!=null)throw new IllegalStateException("区块采集服务已启动或关闭");
        timer=Sched.globalTimer(this::tick,1,1);
    }
    public synchronized Request capture(World world,List<Coordinate> coordinates) {
        return capture(world,coordinates,chunk->List.of());
    }
    public synchronized Request capture(World world,List<Coordinate> coordinates,Extras extras) {
        return capture(world,coordinates,extras,true);
    }
    public synchronized Request metadata(World world,List<Coordinate> coordinates,Extras extras){return capture(world,coordinates,extras,false);}
    private synchronized Request capture(World world,List<Coordinate> coordinates,Extras extras,boolean blocks) {
        Objects.requireNonNull(world);Objects.requireNonNull(coordinates);Objects.requireNonNull(extras);
        if(closed || requests.size()>=limits.maxRequests)throw new RejectedExecutionException("区块采集队列已满或关闭");
        if(coordinates.isEmpty() || coordinates.size()>limits.maxChunksPerRequest || new HashSet<>(coordinates).size()!=coordinates.size())
            throw new IllegalArgumentException("区块采集范围为空、重复或超过限额");
        long bytes=Math.multiplyExact(coordinates.size(),limits.bytesPerChunk);
        long available=limits.maximumSnapshotBytes-reserved;
        if(bytes>available)throw new RejectedExecutionException("区块快照内存预约不足：请求区块="+coordinates.size()+"，请求字节="+bytes+"，可用字节="+Math.max(0,available)+"，总上限="+limits.maximumSnapshotBytes);
        Request request=new Request(world,coordinates,bytes,extras,blocks);reserved+=bytes;requests.add(request);return request;
    }
    /** 由Global调度调用。轮转请求而不是一次派发某位玩家的整片区块。 */
    public void tick() {
        List<Request> expired;
        synchronized(this){if(closed || requests.isEmpty())return;expired=requests.stream().filter(r->!r.released && !r.future.isDone() && System.nanoTime()-r.deadline>=0).toList();}
        for(Request request:expired)release(request,new TimeoutException("区块采集超时"));
        var allowed=budget.reserve(limits.quantumCpuNanos);if(allowed.isEmpty())return;
        long wall=System.nanoTime(),cpu=cpu(),deadline=wall+limits.nanosPerTick;inlineCaptureCpu.set(0L);
        try {
            for(int starts=0;starts<limits.startsPerTick && System.nanoTime()<deadline;starts++) {
                Request request=null;Piece piece=null;
                synchronized(this) {
                    if(closed || inflight>=limits.maxInflight)return;
                    for(Request candidate:requests) {
                        for(Piece next:candidate.pieces.values())if(next.stage==Stage.PENDING || next.stage==Stage.LOADED){request=candidate;piece=next;break;}
                        if(piece!=null)break;
                    }
                    if(piece==null)return;
                    requests.remove(request);requests.add(request);
                    inflight++;request.outstanding++;
                    Stage previous=piece.stage;piece.stage=previous==Stage.LOADED?Stage.CAPTURING:Stage.LOADING;if(piece.started==0)piece.started=System.nanoTime();
                }
                if(piece.stage==Stage.CAPTURING)scheduleCapture(request,piece);
                else loadExisting(request,piece);
            }
        } finally {try{settle(allowed.get(),wall,cpu,inlineCaptureCpu.get());}finally{inlineCaptureCpu.remove();}}
    }
    private void loadExisting(Request request,Piece piece) {
        synchronized(this){if(piece.stage==Stage.DONE || request.released || closed)return;}
        try {
            // 仅owner可查询加载状态并直接复制；worker/Global非owner仍走generate=false异步加载。
            if(Sched.owns(request.world,piece.coordinate.x,piece.coordinate.z) && request.world.isChunkLoaded(piece.coordinate.x,piece.coordinate.z)) {
                synchronized(this){if(piece.stage==Stage.DONE || request.released || closed)return;piece.loaded=System.nanoTime();piece.stage=Stage.CAPTURING;}
                captureOwned(request,piece);return;
            }
            request.world.getChunkAtAsync(piece.coordinate.x,piece.coordinate.z,false).whenComplete((chunk,error)->{
                if(error!=null){finishPiece(request,piece,null,error);return;}
                if(chunk==null){finishPiece(request,piece,null,new IllegalStateException("区块未生成，未请求生成新地形："+piece.coordinate));return;}
                synchronized(this) {
                    if(piece.stage==Stage.DONE || request.released || closed){finishPiece(request,piece,null,new CancellationException("迟到区块加载已失效"));return;}
                    piece.loaded=System.nanoTime();piece.stage=Stage.CAPTURING;
                }
                scheduleCapture(request,piece);
            });
        } catch(Throwable error){finishPiece(request,piece,null,error);if(error instanceof VirtualMachineError fatal)throw fatal;}
    }
    private void scheduleCapture(Request request,Piece piece) {
        synchronized(this){if(piece.stage==Stage.DONE || request.released || closed)return;piece.ownerQueued=System.nanoTime();}
        try {
            if(Sched.owns(request.world,piece.coordinate.x,piece.coordinate.z))captureOwned(request,piece);
            else Sched.region(request.world,piece.coordinate.x,piece.coordinate.z,()->captureOwned(request,piece));
        }
        catch(RuntimeException error){finishPiece(request,piece,null,error);}
    }
    private void captureOwned(Request request,Piece piece) {
        synchronized(this) {
            if(piece.stage==Stage.DONE || piece.active)return;
            if(closed || request.released){finishPiece(request,piece,null,new CancellationException("区域快照任务已失效"));return;}
            if(piece.ownerQueued!=0){piece.ownerWait+=Math.max(0,System.nanoTime()-piece.ownerQueued);piece.ownerQueued=0;}
            piece.active=true;request.activeCaptures++;
        }
        var allowed=budget.reserve(limits.quantumCpuNanos);
        if(allowed.isEmpty()) {
            synchronized(this){piece.retries++;piece.stage=request.released || closed?Stage.DONE:Stage.LOADED;piece.active=false;request.activeCaptures--;inflight--;request.outstanding--;finishRelease(request);}
            return;
        }
        long wall=System.nanoTime(),cpu=cpu();
        Captured captured=null;Throwable failure=null;
        try {
            int x=piece.coordinate.x,z=piece.coordinate.z;
            if(!Sched.owns(request.world,x,z))throw new IllegalStateException("区块快照不在所属区域线程");
            if(!request.world.isChunkLoaded(x,z))throw new IllegalStateException("临时加载票结束后区块已卸载，未同步重新加载："+piece.coordinate);
            long minimum=65536L+(long)(request.world.getMaxHeight()-request.world.getMinHeight())*256*16;
            if(limits.bytesPerChunk<minimum)throw new IllegalStateException("单区块快照预约小于保守尺寸估计："+minimum);
            Chunk chunk=request.world.getChunkAt(x,z); // owner中已确认加载；不会跨线程卸载后偷偷同步加载。
            Extras extras; synchronized(this){extras=request.extras;}
            if(extras==null)throw new CancellationException("区块采集已释放");
            ChunkSnapshot frozen=request.blocks?RuntimeCompat.texturedSnapshot(chunk):null;
            var entities=extras.capture(chunk);
            long used=minimum+entities.stream().mapToLong(e->128L+e.boxes().size()*128L).sum();
            if(used>limits.bytesPerChunk)throw new IllegalStateException("实体加快照的保守内存超过单区块预约");
            captured=new Captured(frozen,wall,System.nanoTime(),entities);
        } catch(Throwable error){failure=error;}
        finally {
            long elapsed=Math.max(0,System.nanoTime()-wall),cost=settle(allowed.get(),wall,cpu,0);synchronized(this){piece.copyWall+=elapsed;piece.copyCpu+=cost;}Long inline=inlineCaptureCpu.get();
            if(inline!=null)inlineCaptureCpu.set(inline+cost);
        }
        finishPiece(request,piece,captured,failure);
        if(failure instanceof VirtualMachineError fatal)throw fatal;
    }
    private void finishPiece(Request request,Piece piece,Captured captured,Throwable error) {
        boolean succeeded=false,failed=false;
        synchronized(this) {
            if(piece.stage==Stage.DONE)return;
            piece.stage=Stage.DONE;inflight--;request.outstanding--;
            if(piece.active){piece.active=false;request.activeCaptures--;}
            if(!request.released && error==null) {
                request.snapshots.put(piece.coordinate,captured);captures++;
                succeeded=request.snapshots.size()==request.pieces.size();
            } else failed=!request.released;
            finishRelease(request);
        }
        if(failed)release(request,error);
        else if(succeeded)request.future.complete(request);
    }
    private void release(Request request,Throwable error) {
        synchronized(this) {
            if(request.released)return;request.released=true;request.snapshots.clear();request.extras=null;
            for(Piece piece:request.pieces.values())if(!piece.active && piece.stage!=Stage.DONE) {
                if(piece.stage==Stage.LOADING || piece.stage==Stage.CAPTURING){inflight--;request.outstanding--;}
                piece.stage=Stage.DONE;
            }
            finishRelease(request);
        }
        request.future.completeExceptionally(error);
    }
    private void finishRelease(Request request) {
        // 正在复制的owner退出前保留预约；尚未运行的迟到任务不再允许分配快照。
        if(request.released && request.activeCaptures==0 && requests.remove(request)){reserved-=request.reservation;request.reservation=0;if(request.memory!=null)request.memory.close();}
    }
    private long cpu(){return meter.isCurrentThreadCpuTimeSupported() && meter.isThreadCpuTimeEnabled()?meter.getCurrentThreadCpuTime():-1;}
    private long settle(CpuBudget.Reservation reservation,long wall,long start,long alreadyCharged) {
        long end=cpu(),elapsed=Math.max(0,System.nanoTime()-wall),cost=start>=0 && end>=start?end-start:elapsed;
        // owner直接执行时，外层Global调度只收费剩余管理成本，不将同一次复制CPU算两遍。
        cost=Math.max(0,cost-alreadyCharged);reservation.settle(cost);
        if(elapsed>limits.nanosPerTick)synchronized(this){overruns++;}return cost;
    }
    public int maximumChunksPerRequest(){return limits.maxChunksPerRequest;}
    public synchronized Metrics metrics(){return new Metrics(requests.size(),inflight,reserved,captures,overruns);}
    @Override public void close() {
        List<Request> active;
        synchronized(this){if(closed)return;closed=true;if(timer!=null)timer.cancel();active=List.copyOf(requests);}
        for(Request request:active)release(request,new CancellationException("区块采集服务关闭"));
    }
}
