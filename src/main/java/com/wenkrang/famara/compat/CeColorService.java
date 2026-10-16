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
    private static final Logger LOG = Logger.getLogger("Famara");
    private static final AtomicLong generation = new AtomicLong();
    private static volatile List<Path> generated = List.of();
    private static volatile List<Path> configured = List.of();
    private static volatile List<Path> external = List.of();
    private static volatile List<Path> defaults = List.of();
    private static volatile Function<String, byte[]> presetModels = path -> null;
    private static volatile Map<String, CeCompat.ItemModel> itemModels = Map.of();
    private static volatile Snapshot active = new Snapshot(PackColorIndex.Result.EMPTY, List.of(), List.of(), Map.of());
    private static volatile Path diagnostics;
    private static volatile String lastFailure = "尚未建立索引";

    static final class Snapshot {
        final PackColorIndex.Result result;
        final List<Path> sources;
        final List<String> sourceIssues;
        final Map<String, CeCompat.ItemModel> models;
        final Map<String, String> missing = new ConcurrentHashMap<>();
        final Instant created = Instant.now();
        Snapshot(PackColorIndex.Result result, List<Path> sources, List<String> sourceIssues,
                 Map<String, CeCompat.ItemModel> models) {
            this.result = result;
            this.sources = List.copyOf(sources);
            this.sourceIssues = List.copyOf(sourceIssues);
            this.models = Map.copyOf(models);
        }
    }

    static Snapshot snapshot() { return active; }
    static long request() { return generation.incrementAndGet(); }
    static void diagnostics(Path path) { diagnostics = path; }

    static synchronized void configure(Collection<Path> roots, Collection<Path> fallbacks,
                                       Function<String, byte[]> presets) {
        configured = normalized(roots);
        defaults = normalized(fallbacks);
        presetModels = presets;
    }

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
        Snapshot previous = active;
        active = new Snapshot(previous.result, previous.sources, previous.sourceIssues, itemModels);
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

    static void rebuild(long ticket) { rebuild(List.of(), ticket); }
    static void rebuild(Collection<Path> preferred, long ticket) {
        List<Path> paths = candidates(preferred);
        ColorInputs inputs = new ColorInputs(com.wenkrang.famara.lib.ColorManager.view(), presetModels);
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
            publish(result, ticket, sources.opened(), sources.issues());
        } catch (Exception e) {
            failed(ticket, "CE 索引重建失败：" + describe(e) + "；位置=" + firstFrame(e) + "；候选来源=" + paths, e);
        }
    }

    private record ColorInputs(com.wenkrang.famara.lib.ColorManager.View palette,
                               Function<String, byte[]> presets) {}

    static synchronized boolean publish(PackColorIndex.Result result, long ticket,
                                        List<Path> sources, List<String> sourceIssues) {
        if (generation.get() != ticket) return false;
        if (!result.hasUsableColors()) {
            lastFailure = "本次来源未解析出任何可用颜色，保留上次有效索引。来源=" + sources
                    + "；来源检查=" + sourceIssues + "；解析问题=" + result.issues();
            LOG.warning(lastFailure.substring(0, Math.min(lastFailure.length(), 1600)));
            writeDiagnostics(active);
            return false;
        }
        active = new Snapshot(result, sources, sourceIssues, itemModels);
        lastFailure = "无";
        LOG.info("CE 自动取色索引完成：" + result.usableItems() + "/" + result.items().size()
                + " 个物品可取色，" + result.blockstates().size() + " 个方块状态定义；实际来源=" + sources);
        result.issues().stream().limit(10).forEach(LOG::warning);
        if (!result.issues().isEmpty()) LOG.warning("完整取色诊断见 plugins/Famarareload/ce-color-index.txt");
        writeDiagnostics(active);
        return true;
    }

    private static synchronized void failed(long ticket, String message) {
        failed(ticket, message, null);
    }

    private static synchronized void failed(long ticket, String message, Throwable cause) {
        if (generation.get() != ticket) return;
        lastFailure = message;
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
