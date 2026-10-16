package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.ColorManager;
import com.wenkrang.famara.render.lib.EntityShape;
import com.wenkrang.famara.render.lib.FaColor;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** CE 无关门面，可选实现仅在 CE 启用时按类名加载。 */
public final class CeCompat {
    public static final int NOT_CUSTOM = Integer.MIN_VALUE;
    public static final int NO_COLOR = Integer.MIN_VALUE + 1;
    private static final Logger LOG = Logger.getLogger("Famara");
    public record ItemModel(String resource, PackColorIndex.ItemState state) {}
    public record BlockColor(int rgb, String id) {}
    public interface Backend {
        default boolean enabled() { return false; }
        default ItemStack buildItem(String id) { return null; }
        default boolean matches(ItemStack stack, String id) { return false; }
        default BlockColor block(BlockData data, Session session) { return new BlockColor(NOT_CUSTOM, null); }
        default EntityShape capture(Entity entity, Session session, Set<UUID> seen) { return null; }
        default void register() {}
        default boolean install() { return false; }
        default void ensureLoaded() {}
        default void refreshResources(Path report) {}
        default void refreshItemModels() {}
        default void close() {}
    }
    private static final Backend NONE = new Backend() {};
    private static volatile Backend backend = NONE;
    private static volatile Plugin owner;
    private static volatile String status = "CE 扩展未初始化，核心独立模式";
    private CeCompat() {}

    public static void initialize(Plugin plugin) {
        owner = plugin;
        Plugin ce = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (ce == null || !ce.isEnabled()) {
            backend = NONE;
            status = ce == null ? "未安装 CraftEngine，使用独立模式" : "CraftEngine 未启用，使用独立模式";
            LOG.info(status);
            return;
        }
        Backend candidate = null;
        try {
            candidate = (Backend) Class.forName("com.wenkrang.famara.compat.CraftEngineBackend", true,
                    CeCompat.class.getClassLoader()).getDeclaredConstructor().newInstance();
            candidate.register();
            backend = candidate;
            status = "CE 可选兼容已启用：" + ce.getDescription().getVersion();
            LOG.info(status);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            Throwable cause = error instanceof InvocationTargetException invocation ? invocation.getCause() : error;
            if (cause instanceof Error fatal && !(fatal instanceof LinkageError)) throw fatal;
            if (candidate != null) {
                try { candidate.close(); }
                catch (RuntimeException | LinkageError cleanup) { LOG.warning("清理 CE 监听器失败：" + cleanup); }
            }
            backend = NONE;
            status = "CE API 不兼容，已停用扩展并保留独立功能：" + cause;
            LOG.warning(status);
        }
    }
    public static boolean enabled() { return backend.enabled(); }
    public static String status() { return status; }
    static void backendForTests(Backend value) { backend = value == null ? NONE : value; }
    private static synchronized void disable(Throwable error) {
        if (backend == NONE) return;
        Backend previous = backend;
        backend = NONE;
        try { previous.close(); }
        catch (RuntimeException | LinkageError cleanup) { LOG.warning("清理 CE 兼容失败：" + cleanup); }
        status = "CE 扩展异常，独立功能继续可用：" + error;
        LOG.warning(status);
        Plugin current = owner;
        if (current != null && current.isEnabled()) {
            com.wenkrang.famara.lib.Sched.global(() -> CompatibilityReport.write(current));
        }
    }
    public static ItemStack integrationItem(String id) {
        if (!enabled()) return null;
        try { return backend.buildItem(id); }
        catch (RuntimeException | LinkageError error) { disable(error); return null; }
    }
    public static boolean integrationMatches(ItemStack stack, String id) {
        if (!enabled()) return false;
        try { return backend.matches(stack, id); }
        catch (RuntimeException | LinkageError error) { disable(error); return false; }
    }
    public static boolean install() {
        if (!enabled()) return false;
        try { return backend.install(); }
        catch (RuntimeException | LinkageError error) { disable(error); return false; }
    }
    public static void ensureLoaded() {
        if (enabled()) {
            try { backend.ensureLoaded(); }
            catch (RuntimeException | LinkageError error) { disable(error); }
        }
        if (!enabled()) {
            com.wenkrang.famara.loader.LoadItem.loadItem();
            com.wenkrang.famara.loader.LoadRecipe.loadRecipe();
        }
    }
    public static void refreshResources(Path report) {
        if (!enabled()) return;
        try { backend.refreshResources(report); }
        catch (RuntimeException | LinkageError error) { disable(error); }
    }
    public static void refreshItemModels() {
        if (!enabled()) return;
        try { backend.refreshItemModels(); }
        catch (RuntimeException | LinkageError error) { disable(error); }
    }
    public static void close() {
        Backend previous = backend; backend = NONE; owner = null;
        try { previous.close(); }
        catch (RuntimeException | LinkageError error) { LOG.warning("关闭 CE 可选兼容失败：" + error); }
    }

    /** 仅含通用类型，无 CE 也能创建场景快照。 */
    public static final class Session {
        public final ColorManager.View palette;
        final PackColorIndex.Result textures;
        final Map<String, ItemModel> models;
        final CeColorService.Snapshot snapshot;
        final ConcurrentHashMap<String, Optional<Integer>> definitions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<BlockData, BlockColor> blocks = new ConcurrentHashMap<>();
        private final Backend provider;
        public Session(ColorManager.View palette, PackColorIndex.Result textures) {
            this(palette, new CeColorService.Snapshot(textures, List.of(), List.of(), Map.of()));
        }
        Session(ColorManager.View palette, CeColorService.Snapshot snapshot) {
            this.palette = palette; this.snapshot = snapshot; this.textures = snapshot.result;
            this.models = snapshot.models; this.provider = backend;
        }
        public BlockColor block(BlockData data) {
            if (data == null || !enabled() || !provider.enabled()) return new BlockColor(NOT_CUSTOM, null);
            return blocks.computeIfAbsent(data, state -> {
                try { return provider.block(state, this); }
                catch (LinkageError error) { disable(error); return new BlockColor(NOT_CUSTOM, null); }
                catch (RuntimeException error) {
                    LOG.warning("CE 方块取色失败：" + state.getAsString() + "：" + error.getMessage());
                    CeColorService.missing(snapshot, state.getAsString(), "state=" + state.getAsString() + "，异常=" + error);
                    return new BlockColor(NO_COLOR, state.getAsString());
                }
            });
        }
    }
    public static Session session() { return new Session(ColorManager.view(), CeColorService.snapshot()); }
    public static EntityShape capture(Entity entity, Session session, Set<UUID> seen) {
        if (!enabled() || !session.provider.enabled()) return null;
        try { return session.provider.capture(entity, session, seen); }
        catch (LinkageError error) { disable(error); return null; }
    }
    public static File defaultPackZip() { return new File(Bukkit.getPluginsFolder(), "CraftEngine/generated/resource_pack.zip"); }
    public static long requestRebuild() { return CeColorService.request(); }
    public static void rebuildFromZip(File zip) { rebuildFromZip(zip, requestRebuild()); }
    public static void rebuildFromZip(File zip, long ticket) { CeColorService.rebuild(zip == null ? List.of() : List.of(zip.toPath()), ticket); }
    public static void rebuildFromFolder(Path folder) { rebuildFromFolder(folder, requestRebuild()); }
    public static void rebuildFromFolder(Path folder, long ticket) { CeColorService.rebuild(folder == null ? List.of() : List.of(folder), ticket); }
    public static void rebuildLast(long ticket) { if (enabled()) CeColorService.rebuild(ticket); }
    static boolean publish(PackColorIndex.Result result, long ticket) { return CeColorService.publish(result, ticket, List.of(), List.of()); }
    public static long generatedSources(Path folder, Path zip) { return CeColorService.generated(folder, zip); }
    public static void externalSources(Collection<Path> folders, Collection<Path> zips) { CeColorService.external(folders, zips); }
    public static void writeDiagnostics(Session session) { CeColorService.writeDiagnostics(session.snapshot); }

    static BlockColor resolvePalette(String id, String visual, String itemId, Integer fallback, Session session) {
        Integer rgb = session.palette.configured(id);
        if (rgb == null) rgb = session.textures.stateColor(visual);
        if (rgb == null) rgb = indexedItem(itemId, session);
        if (rgb == null && !id.equals(itemId)) rgb = indexedItem(id, session);
        if (rgb == null) rgb = fallback;
        if (rgb == null) {
            ItemModel model = session.models.get(itemId);
            CeColorService.missing(session.snapshot, id + "|" + visual,
                    "id=" + id + "，visual=" + visual + "，关联物品=" + itemId
                            + "，item_model=" + (model == null ? "<无组件映射>" : model.resource())
                            + "，索引定义=" + session.textures.items().containsKey(itemId) + "，来源=" + session.snapshot.sources);
        }
        return new BlockColor(rgb == null ? NO_COLOR : rgb, id);
    }
    private static Integer indexedItem(String id, Session session) {
        Integer manual = session.palette.configured(id);
        if (manual != null) return manual;
        ItemModel model = session.models.get(id);
        if (model != null) {
            Integer rgb = session.textures.itemColor(model.resource(), model.state());
            if (rgb != null || (RuntimeCompat.modernItemModels() && session.textures.items().containsKey(model.resource()))) return rgb;
        }
        return session.textures.itemColor(id, PackColorIndex.ItemState.EMPTY);
    }
    public static double[] transformedBox(Matrix4f matrix, float hx, float hy, float hz) {
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < 8; i++) {
            Vector3f p = matrix.transformPosition(new Vector3f((i & 1) == 0 ? -hx : hx,
                    (i & 2) == 0 ? -hy : hy, (i & 4) == 0 ? -hz : hz));
            if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.z)) return null;
            box[0] = Math.min(box[0], p.x); box[1] = Math.min(box[1], p.y); box[2] = Math.min(box[2], p.z);
            box[3] = Math.max(box[3], p.x); box[4] = Math.max(box[4], p.y); box[5] = Math.max(box[5], p.z);
        }
        return box[3] > box[0] && box[4] > box[1] && box[5] > box[2] ? box : null;
    }
    public static FaColor rgbToColor(int rgb) { return new FaColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255); }
}
