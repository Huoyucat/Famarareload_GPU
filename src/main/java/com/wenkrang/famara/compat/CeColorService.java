package com.wenkrang.famara.compat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;

/** CE 自动发现来源、异步建立索引和诊断发布。运行时不依赖任何客户端目录。 */
final class CeColorService {
    private static final Logger LOG = Logger.getLogger("FamaraV2");
    private static final AtomicLong generation = new AtomicLong();
    private static volatile List<Path> generated = List.of();
    private static volatile List<Path> configured = List.of();
    private static volatile List<Path> external = List.of();
    private static volatile List<Path> defaults = List.of();
    private static volatile List<Path> actorSupplemental = List.of();
    private static volatile Function<String, byte[]> presetModels = path -> null;
    private static long modelRevision;
    private static volatile Map<String, CeCompat.ItemModel> itemModels = Map.of();
    private static volatile Snapshot active = new Snapshot(PackColorIndex.Result.EMPTY, List.of(), List.of(), Map.of());
    private static volatile Path diagnostics;
    private static boolean policyLogged;
    static synchronized void memory(com.wenkrang.famara.render.schedule.RenderMemoryBudget ignored){
        if(!policyLogged){policyLogged=true;LOG.info("CE旧取色使用独立280MiB预约，与V2共享512MiB分开计费；插件总预约可超过512MiB");}
    }
    private static final com.wenkrang.famara.render.schedule.RenderMemoryBudget independent=new com.wenkrang.famara.render.schedule.RenderMemoryBudget(ColorWorkBudget.MAXIMUM);
    static com.wenkrang.famara.render.schedule.RenderMemoryBudget.Snapshot independentMemory(){return independent.snapshot();}
    private static CeColorBuildQueue queue;
    private static volatile long failedEpoch=-1;
    private static volatile String lastFailure = "尚未建立索引";

    static final class Snapshot {
        com.wenkrang.famara.render.schedule.SharedMemory memory;
        final long visualEpoch;
        final PackColorIndex.Result result;
        final List<Path> sources;
        final List<String> sourceIssues;
        final Map<String, CeCompat.ItemModel> models;
        final Map<String, String> missing = new ConcurrentHashMap<>();
        final Instant created = Instant.now();
        Snapshot(PackColorIndex.Result result, List<Path> sources, List<String> sourceIssues,
                 Map<String, CeCompat.ItemModel> models) {
            this(result,sources,sourceIssues,models,0);
        }
        Snapshot(PackColorIndex.Result result,List<Path> sources,List<String> sourceIssues,Map<String,CeCompat.ItemModel> models,long visualEpoch) {
            this.visualEpoch=visualEpoch;
            this.result = result;
            this.sources = List.copyOf(sources);
            this.sourceIssues = List.copyOf(sourceIssues);
            this.models = Map.copyOf(models);
        }
    }

    static synchronized Snapshot snapshot() { return active; }
    static long request() { return generation.incrementAndGet(); }
    static void diagnostics(Path path) { diagnostics = path; }

    static synchronized void configure(Collection<Path> roots, Collection<Path> fallbacks,
                                       Function<String, byte[]> presets) {
        configured = normalized(roots);
        defaults = normalized(fallbacks);
        presetModels = presets;
    }

    /** Inactive CE packs stay available as lazy actor-asset fallbacks without entering block compilation. */
    static synchronized void actorSupplemental(Collection<Path> roots) { actorSupplemental=normalized(roots); }

    static synchronized void external(Collection<Path> folders, Collection<Path> zips) {
        List<Path> paths = new ArrayList<>(folders);
        paths.addAll(zips);
        external = normalized(paths);
    }

    static synchronized long generated(Path folder, Path zip) {
        List<Path> paths = new ArrayList<>();
        // 优先与实际下发内容一致的完整 ZIP，目录及外部包补齐缺失文件。
        if (zip != null) paths.add(zip);
        if (folder != null) paths.add(folder);
        generated = normalized(paths);
        return request();
    }

    static synchronized void models(Map<String, CeCompat.ItemModel> models) {
        itemModels = Map.copyOf(models);
        modelRevision++;
        // 新映射不能与旧取色结果拼成假同代会话；完整重建成功后再一次发布。
    }

    private static List<Path> normalized(Collection<Path> paths) {
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path path : paths) {
            if (path == null) continue;
            try { normalized.add(path.toAbsolutePath().normalize()); }
            catch (RuntimeException e) { LOG.warning("跳过已失效的 CE 临时来源：" + path + "：" + e); }
        }
        return List.copyOf(normalized);
    }

    static synchronized List<Path> candidates(Collection<Path> preferred) {
        List<Path> paths = new ArrayList<>(preferred);
        paths.addAll(generated);
        paths.addAll(defaults);
        paths.addAll(configured);
        paths.addAll(external);
        return normalized(paths);
    }

    /** 异步V2不能持有CE事件结束即关闭的ZIP/内存文件系统Path，只使用可重新打开的磁盘来源。 */
    static synchronized List<Path> persistentCandidates() {
        List<Path> result=new ArrayList<>();
        for(Path path:candidates(List.of())) {
            if(path.getFileSystem()!=java.nio.file.FileSystems.getDefault() || !path.getFileSystem().isOpen())continue;
            result.add(path);
        }
        return List.copyOf(result);
    }
    static synchronized List<Path> persistentActorCandidates() {
        List<Path> result=new ArrayList<>(candidates(List.of()));result.addAll(actorSupplemental);
        List<Path> persistent=new ArrayList<>();for(Path path:normalized(result))if(path.getFileSystem()==java.nio.file.FileSystems.getDefault() && path.getFileSystem().isOpen())persistent.add(path);
        return List.copyOf(persistent);
    }
    static synchronized void performance(com.wenkrang.famara.render.schedule.CpuBudget budget,com.wenkrang.famara.render.schedule.BudgetedAssetLoader.Limits limits) {
        if(queue==null)queue=new CeColorBuildQueue(budget,limits);else queue.configure(budget,limits);
    }
    static synchronized void closeQueue(){generation.incrementAndGet();if(queue!=null)queue.close();queue=null;if(active.memory!=null)active.memory.close();active=new Snapshot(PackColorIndex.Result.EMPTY,List.of(),List.of(),Map.of());}
    static boolean ready(long epoch){return active.visualEpoch==epoch;}
    static String failure(long epoch){return failedEpoch==epoch?lastFailure:null;}
    static java.util.concurrent.CompletionStage<Void> schedule(long ticket){return schedule(List.of(),ticket);}
    static java.util.concurrent.CompletionStage<Void> schedule(Collection<Path> preferred,long ticket) {
        CeColorBuildQueue worker;
        synchronized(CeColorService.class) {
            if(ticket!=generation.get())return java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("旧色请求已过期"));
            if(queue==null)performance(new com.wenkrang.famara.render.schedule.CpuBudget(1000,50,System::nanoTime),new com.wenkrang.famara.render.schedule.BudgetedAssetLoader.Limits(128,1000000,1000000,8000000,120000000000L));
            worker=queue;
        }
        var future=worker.offer(ticket,()->queuedWork(ticket,preferred));
        future.whenComplete((ignored,error)->{
            Throwable cause=error;while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null)cause=cause.getCause();
            if(cause!=null && !(cause instanceof java.util.concurrent.CancellationException))failed(ticket,"CE颜色队列失败："+cause,cause);
        });
        return future;
    }
    private static CeColorBuildQueue.Work queuedWork(long ticket,Collection<Path> preferred) {
        final List<Path> paths;final ColorInputs inputs;final Map<String,CeCompat.ItemModel> frozenModels;final long revision,epoch;
        synchronized(CeColorService.class){paths=candidates(preferred);inputs=new ColorInputs(com.wenkrang.famara.lib.ColorManager.view(),presetModels);frozenModels=itemModels;revision=modelRevision;epoch=CeCompat.visualEpoch();}
        final var seed=new java.util.concurrent.atomic.AtomicReference<>(frozenModels);
        return new CeColorBuildQueue.Work() {
            Map<String,CeCompat.ItemModel> modelView=seed.getAndSet(null);
            CePackSources sources;PackColorIndex.Cursor cursor;ColorSourceCache cache;ColorRootSelector roots;ColorWorkBudget work;
            long start=System.nanoTime();boolean rootsReady,indexed;
            com.wenkrang.famara.render.schedule.RenderMemoryBudget.Lease allocation;
            public boolean current(){return generation.get()==ticket && CeCompat.visualEpoch()==epoch && sameRevision();}
            private boolean sameRevision(){synchronized(CeColorService.class){return modelRevision==revision;}}
            private void check(){if(!current())throw new java.util.concurrent.CancellationException("CE颜色输入代已过期");}
            public boolean step(int operations,long deadline)throws Exception {
                check();
                if(work==null) {
                    allocation=independent.reserve("legacy-color-build",ColorWorkBudget.TRANSIENT);work=new ColorWorkBudget(allocation::resize);roots=new ColorRootSelector(modelView,work);
                    LOG.info("旧CE取色临时独立预算：存活256MiB＋解码/解析24MiB＝280MiB；不计入V2共享512MiB，插件合计预约允许超过512MiB");
                }
                if(!rootsReady) {
                    rootsReady=roots.step(operations,deadline,System::nanoTime,this::current);
                    if(rootsReady) {
                        var compact=roots.models();
                        synchronized(CeColorService.class){check();itemModels=compact;modelView=compact;}
                        roots.releaseInput();
                    }
                    return false;
                }
                if(sources==null)sources=CePackSources.begin(paths,this::current,work,revision==0?null:roots);
                if(!indexed){indexed=sources.step(operations,deadline,System::nanoTime);return false;}
                if(cache==null){cache=new ColorSourceCache(sources.source(),inputs.presets,revision==0?null:roots,this::current,work,true);cursor=PackColorIndex.cursor(cache,inputs.palette::vanilla,work);return false;}
                return cursor.step(operations,deadline,System::nanoTime,this::current);
            }
            public void publish(){
                check();var result=cursor.result();var opened=sources.opened();var issues=sources.issues();long traffic=cache.trafficBytes(),cached=cache.cachedBytes();
                work.retain("source-status",issues.stream().mapToLong(line->128L+line.length()*4L).sum()+opened.size()*512L);
                int uniqueModels=roots.uniqueResources(),actualRoots=sources.rootCount();var before=work.domains();var zipMetrics=sources.metrics();
                cursor=null;cache.close();cache=null;sources.close();sources=null;roots.close();roots=null;
                synchronized(CeColorService.class){if(CeColorService.publish(result,ticket,opened,issues,modelView,revision,epoch) && allocation!=null){allocation.resize(Math.max(65536,work.bytes("results")+work.bytes("issues")+work.bytes("item-model-view")+work.bytes("source-status")));allocation.transfer("legacy-color-retained");active.memory=new com.wenkrang.famara.render.schedule.SharedMemory(allocation);allocation=null;}}
                LOG.info("CE取色准备wall="+(System.nanoTime()-start)/1e6+"ms；物品映射="+modelView.size()+"，唯一model键="+uniqueModels+"，实际根="+actualRoots+"，ZIP目录指标="+zipMetrics+"；累计读取流量="+traffic+"，发布前缓存字节="+cached+"，独立280MiB封套峰值="+work.peak()+"，域分解="+before+"；独立账本="+independent.snapshot()+"；"+queueMetrics());
            }
            public void close(){cursor=null;if(cache!=null)cache.close();cache=null;if(sources!=null)sources.close();sources=null;if(roots!=null)roots.close();roots=null;if(allocation!=null)allocation.close();allocation=null;}
        };
    }
    static synchronized String queueMetrics(){return queue==null?"队列已关闭":queue.metrics().toString();}
    static void rebuild(long ticket) { rebuild(List.of(), ticket); }
    static void rebuild(Collection<Path> preferred, long ticket) {
        List<Path> paths;ColorInputs inputs;Map<String,CeCompat.ItemModel> frozenModels;long revision,epoch;
        synchronized(CeColorService.class) {
            paths=candidates(preferred);inputs=new ColorInputs(com.wenkrang.famara.lib.ColorManager.view(),presetModels);
            frozenModels=itemModels;revision=modelRevision;epoch=CeCompat.visualEpoch();
        }
        try (CePackSources sources = CePackSources.open(paths)) {
            PackColorIndex.PackSource layered = sources.source();
            PackColorIndex.PackSource withPresets = new PackColorIndex.PackSource() {
                public Collection<String> list() throws IOException { return layered.list(); }
                public byte[] read(String path) throws IOException {
                    byte[] bytes = layered.read(path);
                    return bytes != null ? bytes : inputs.presets.apply(path);
                }
            };
            PackColorIndex.Result result = PackColorIndex.build(withPresets, inputs.palette::vanilla);
            publish(result,ticket,sources.opened(),sources.issues(),frozenModels,revision,epoch);
        } catch (Exception e) {
            failed(ticket, "CE 索引重建失败：" + describe(e) + "；位置=" + firstFrame(e) + "；候选来源=" + paths, e);
        }
    }

    private record ColorInputs(com.wenkrang.famara.lib.ColorManager.View palette,
                               Function<String, byte[]> presets) {}

    static synchronized boolean publish(PackColorIndex.Result result, long ticket,
                                        List<Path> sources, List<String> sourceIssues) {
        return publish(result,ticket,sources,sourceIssues,itemModels,modelRevision,CeCompat.visualEpoch());
    }
    private static synchronized boolean publish(PackColorIndex.Result result,long ticket,List<Path> sources,List<String> sourceIssues,
                                                Map<String,CeCompat.ItemModel> frozenModels,long revision,long epoch) {
        if(generation.get()!=ticket || modelRevision!=revision || CeCompat.visualEpoch()!=epoch)return false;
        if (!result.hasUsableColors()) {
            lastFailure = "本次来源未解析出任何可用颜色，保留上次有效索引。来源=" + sources
                    + "；来源检查=" + sourceIssues + "；解析问题=" + result.issues();
            failedEpoch=epoch;LOG.warning(lastFailure.substring(0, Math.min(lastFailure.length(), 1600)));
            writeDiagnostics(active);
            return false;
        }
        Snapshot previous=active;active=new Snapshot(result,sources,sourceIssues,frozenModels,epoch);if(previous.memory!=null)previous.memory.close();
        failedEpoch=-1;lastFailure = "无";
        LOG.info("CE 自动取色索引完成：" + result.usableItems() + "/" + result.items().size()
                + " 个物品可取色，" + result.blockstates().size() + " 个方块状态定义；实际来源=" + sources);
        result.issues().stream().limit(10).forEach(LOG::warning);
        if (!result.issues().isEmpty()) LOG.warning("完整取色诊断见插件实际数据目录下的ce-color-index.txt");
        writeDiagnostics(active);
        return true;
    }

    private static synchronized void failed(long ticket, String message) {
        failed(ticket, message, null);
    }

    private static synchronized void failed(long ticket, String message, Throwable cause) {
        if (generation.get() != ticket) return;
        failedEpoch=CeCompat.visualEpoch();lastFailure = message;
        LOG.warning(message);
        // 只记录 getMessage() 会丢失“无消息异常”的来源；补全异常类名与完整堆栈，便于定位。
        if (cause != null) LOG.warning("CE 索引重建异常堆栈：" + System.lineSeparator() + stackTrace(cause));
        writeDiagnostics(active);
    }

    /** 生成包含异常类名的描述；无消息的异常（如 NullPointerException 空消息）也能被识别。 */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getName() + (message == null || message.isBlank() ? "（无消息）" : ": " + message);
    }

    private static String firstFrame(Throwable e) {
        StackTraceElement[] frames = e.getStackTrace();
        return frames.length == 0 ? "未知" : frames[0].toString();
    }

    private static String stackTrace(Throwable e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    static void missing(Snapshot snapshot, String key, String details) {
        if (snapshot != active || snapshot.missing.size() >= 1000) return;
        if (snapshot.missing.putIfAbsent(key, details) == null)
            LOG.warning("CE 自动取色未命中：" + details + "；完整诊断见 ce-color-index.txt");
    }

    static synchronized void writeDiagnostics(Snapshot snapshot) {
        if (snapshot != active || diagnostics == null) return;
        List<String> lines = new ArrayList<>();
        lines.add("CE 自动取色诊断");
        lines.add("有效索引建立时间：" + snapshot.created);
        lines.add("物品可用/总数：" + snapshot.result.usableItems() + "/" + snapshot.result.items().size());
        lines.add("方块状态定义：" + snapshot.result.blockstates().size());
        lines.add("旧整数 CMD 模型定义：" + snapshot.result.legacyItems().size());
        lines.add("CE 实际物品模型映射：" + snapshot.models.size());
        lines.add("最近一次索引失败：" + lastFailure);
        lines.add("以上计数为最后成功发布的索引；0/0不代表CE注册表没有物品。");
        lines.add("本次CE物品模型输入："+itemModels.size());
        lines.add("旧色独立账本（256MiB存活＋24MiB临时，不计V2共享账本）："+independent.snapshot());
        lines.add("");
        lines.add("本次实际读取来源（前面的同名文件优先）：");
        snapshot.sources.forEach(p -> lines.add(p.toString()));
        lines.add("");
        lines.add("CE 已加载配置资源目录：");
        configured.forEach(p -> lines.add(p.toString()));
        lines.add("CE 缓存事件注册的外部包：");
        external.forEach(p -> lines.add(p.toString()));
        lines.add("");
        lines.add("来源检查：");
        lines.addAll(snapshot.sourceIssues);
        lines.add("模型/纹理解析问题：");
        lines.addAll(snapshot.result.issues());
        lines.add("");
        lines.add("拍照时未知 CE 对象（包含视觉状态、关联物品和模型资源键）：");
        snapshot.missing.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> lines.add(e.getValue()));
        try {
            Files.createDirectories(diagnostics.toAbsolutePath().getParent());
            Files.writeString(diagnostics, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        } catch (IOException e) { LOG.warning("写入 CE 取色诊断失败：" + e.getMessage()); }
    }
}
