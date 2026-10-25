package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.ColorManager;
import com.wenkrang.famara.render.lib.EntityShape;
import com.wenkrang.famara.render.lib.FaColor;
import com.wenkrang.famara.render.asset.*;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final Logger LOG = Logger.getLogger("FamaraV2");
    public record ItemModel(String resource, PackColorIndex.ItemState state) {}
    public record BlockColor(int rgb, String id) {}
    public record VisualState(String serverState,String material,String id,String vanillaState,String visualState,boolean customBlock) {
        public VisualState(String serverState,String material,String id,String vanillaState,String visualState){this(serverState,material,id,vanillaState,visualState,true);}
    }
    public interface VisualCursor {
        List<VisualState> next(int maximum);
        boolean done();
    }
    public record VisualSources(long epoch,boolean cePresent,boolean adapterAvailable,List<Path> paths,List<Path> actorAssetPaths) {
        public VisualSources(long epoch,boolean cePresent,boolean adapterAvailable,List<Path> paths){this(epoch,cePresent,adapterAvailable,paths,paths);}
        public VisualSources {paths=List.copyOf(paths);actorAssetPaths=List.copyOf(actorAssetPaths);}
    }
    private static final Object VISUAL_LOCK=new Object();
    private static final AtomicLong VISUAL_EPOCH=new AtomicLong(1);
    private static volatile boolean cePresent;
    public static long visualEpoch(){return VISUAL_EPOCH.get();}
    public static void invalidateVisualResources(){VISUAL_EPOCH.incrementAndGet();}
    public static VisualSources visualSources() {
        synchronized(VISUAL_LOCK) {
            return new VisualSources(visualEpoch(),cePresent,backend.enabled(),CeColorService.persistentCandidates(),CeColorService.persistentActorCandidates());
        }
    }
    public static VisualCursor openVisualCursor(long expectedEpoch) {
        Backend provider=backend;
        if(expectedEpoch!=visualEpoch())throw new IllegalStateException("CE资源正在重载");
        if(!cePresent)return new VisualCursor(){public List<VisualState> next(int n){return List.of();}public boolean done(){return true;}};
        if(!provider.enabled() || provider.reloading())throw new IllegalStateException("CE视觉接口不可用或正在重载");
        VisualCursor cursor=provider.visualCursor();
        return new VisualCursor() {
            private void check(){if(expectedEpoch!=visualEpoch() || !provider.enabled() || provider.reloading())throw new IllegalStateException("CE视觉状态在采集期间改变");}
            public List<VisualState> next(int n){check();List<VisualState> value=List.copyOf(cursor.next(n));check();return value;}
            public boolean done(){check();return cursor.done();}
        };
    }
    public interface Backend {
        default boolean enabled() { return false; }
        default boolean reloading(){return false;}
        default VisualCursor visualCursor(){throw new UnsupportedOperationException("此CE后端缺少V2视觉注册表能力");}
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
        cePresent=ce!=null;invalidateVisualResources();
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
    static void backendForTests(Backend value) { backend=value==null?NONE:value;cePresent=value!=null;invalidateVisualResources(); }
    private static synchronized void disable(Throwable error) {
        if (backend == NONE) return;
        invalidateVisualResources();
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
        try { synchronized(VISUAL_LOCK){invalidateVisualResources();try{backend.refreshResources(report);}finally{invalidateVisualResources();}} }
        catch (RuntimeException | LinkageError error) { disable(error); }
    }
    public static void refreshItemModels() {
        if (!enabled()) return;
        try {
            synchronized(VISUAL_LOCK){invalidateVisualResources();try{backend.refreshItemModels();}finally{invalidateVisualResources();}}
            Plugin plugin=owner;
            if(plugin!=null && plugin.isEnabled())scheduleRebuild(requestRebuild());
        }
        catch (RuntimeException | LinkageError error) { disable(error); }
    }
    public static void unavailable() {
        synchronized(VISUAL_LOCK) {
            invalidateVisualResources();CeColorService.request();Backend previous=backend;backend=NONE;cePresent=true;
            try{previous.close();}catch(RuntimeException | LinkageError error){LOG.warning("清理已停用CE兼容失败："+error);}
            status="CraftEngine已停用，V2拒绝按原版载体继续拍摄";
        }
    }
    public static void close() {
        CeColorService.closeQueue();invalidateVisualResources();
        Backend previous = backend; backend = NONE; owner = null;
        try { previous.close(); }
        catch (RuntimeException | LinkageError error) { LOG.warning("关闭 CE 可选兼容失败：" + error); }
    }

    /** 仅含通用类型，无 CE 也能创建场景快照。 */
    public static final class Session implements AutoCloseable {
        private AutoCloseable memory;
        public void close(){if(memory!=null)try{memory.close();}catch(Exception error){throw new IllegalStateException(error);}memory=null;definitions.clear();blocks.clear();}
        public final ColorManager.View palette;
        final PackColorIndex.Result textures;
        final Map<String, ItemModel> models;
        final CeColorService.Snapshot snapshot;
        final ConcurrentHashMap<String, Optional<Integer>> definitions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<BlockData, BlockColor> blocks = new ConcurrentHashMap<>();
        private final Backend provider;
        final boolean frozen;
        final long visualEpoch;
        public Session(ColorManager.View palette, PackColorIndex.Result textures) {
            this(palette, new CeColorService.Snapshot(textures, List.of(), List.of(), Map.of()));
        }
        Session(ColorManager.View palette, CeColorService.Snapshot snapshot) {
            this(palette,snapshot,false,0);
        }
        private Session(ColorManager.View palette,CeColorService.Snapshot snapshot,boolean frozen,long epoch) {
            this.frozen=frozen;visualEpoch=epoch;
            this.palette = palette; this.snapshot = snapshot;memory=snapshot.memory==null?null:snapshot.memory.retain(); this.textures = snapshot.result;
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
    public static Session frozenSession(long epoch) {
        if(epoch!=visualEpoch())throw new IllegalStateException("CE视觉代已变化");
        CeColorService.Snapshot snapshot=CeColorService.snapshot();
        if(cePresent && (!enabled() || snapshot.visualEpoch!=epoch))throw new IllegalStateException("同代CE家具颜色索引尚未就绪");
        return new Session(ColorManager.view(),snapshot,true,epoch);
    }
    public static EntityShape captureFrozen(Entity entity,Session session,Set<UUID> seen) {
        if(!session.frozen)throw new IllegalArgumentException("需要冻结的CE会话");
        if(session.visualEpoch!=visualEpoch())throw new IllegalStateException("CE在实体采集期间变化");
        if(!cePresent)return null;
        if(!session.provider.enabled() || session.provider.reloading())throw new IllegalStateException("CE正在停用或重载");
        EntityShape result=session.provider.capture(entity,session,seen);
        if(session.visualEpoch!=visualEpoch())throw new IllegalStateException("CE在实体采集期间变化");
        return result;
    }
    public static EntityShape capture(Entity entity, Session session, Set<UUID> seen) {
        if (!enabled() || !session.provider.enabled()) return null;
        try { return session.provider.capture(entity, session, seen); }
        catch (LinkageError error) { disable(error); return null; }
    }
    public static File defaultPackZip() { return new File(Bukkit.getPluginsFolder(), "CraftEngine/generated/resource_pack.zip"); }
    /** 旧色暂用自己的280MiB有界账本；该参数仅为旧调用方兼容，不转借V2共享预算。 */
    public static void memoryBudget(com.wenkrang.famara.render.schedule.RenderMemoryBudget memory){CeColorService.memory(memory);}
    public static void loadingBudget(com.wenkrang.famara.render.schedule.CpuBudget budget,com.wenkrang.famara.render.schedule.BudgetedAssetLoader.Limits limits){CeColorService.performance(budget,limits);}
    public static boolean colorsReady(long epoch){return !cePresent || CeColorService.ready(epoch);}
    public static String colorsFailure(long epoch){return cePresent?CeColorService.failure(epoch):null;}
    public static void scheduleRebuild(long ticket){if(enabled())CeColorService.schedule(ticket);}
    public static long requestRebuild() { return CeColorService.request(); }
    public static void rebuildFromZip(File zip) { rebuildFromZip(zip, requestRebuild()); }
    public static void rebuildFromZip(File zip, long ticket) { if(enabled())CeColorService.schedule(zip == null ? List.of() : List.of(zip.toPath()), ticket); }
    public static void rebuildFromFolder(Path folder) { rebuildFromFolder(folder, requestRebuild()); }
    public static void rebuildFromFolder(Path folder, long ticket) { if(enabled())CeColorService.schedule(folder == null ? List.of() : List.of(folder), ticket); }
    public static void rebuildLast(long ticket) { if (enabled()) CeColorService.schedule(ticket); }
    static boolean publish(PackColorIndex.Result result, long ticket) { return CeColorService.publish(result, ticket, List.of(), List.of()); }
    public static long generatedSources(Path folder,Path zip) {synchronized(VISUAL_LOCK){invalidateVisualResources();try{return CeColorService.generated(folder,zip);}finally{invalidateVisualResources();}}}
    public static void externalSources(Collection<Path> folders,Collection<Path> zips) {synchronized(VISUAL_LOCK){invalidateVisualResources();try{CeColorService.external(folders,zips);}finally{invalidateVisualResources();}}}
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
