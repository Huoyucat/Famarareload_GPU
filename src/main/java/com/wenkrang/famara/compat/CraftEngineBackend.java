package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.ColorManager;
import com.wenkrang.famara.render.lib.EntityShape;
import com.wenkrang.famara.render.lib.FaColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemDisplayFurnitureElementConfig;
import net.momirealms.craftengine.bukkit.entity.furniture.element.ItemFurnitureElementConfig;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.entity.culling.CullingData;
import net.momirealms.craftengine.core.entity.furniture.FurnitureVariant;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElementConfig;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.pack.AbstractPackManager;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.MapColor;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.bukkit.Bukkit;
import com.wenkrang.famara.compat.CeCompat.Session;
import com.wenkrang.famara.compat.CeCompat.ItemModel;
import com.wenkrang.famara.compat.CeCompat.BlockColor;
import static com.wenkrang.famara.compat.CeCompat.*;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 只在 CE 已启用且 API 检测成功后加载的隔离实现。 */
public final class CraftEngineBackend implements CeCompat.Backend {
    private static final Logger LOG = Logger.getLogger("Famara");
    private boolean active = true;
    private com.wenkrang.famara.loader.LoadCraftEngine listener;

    public CraftEngineBackend() throws ReflectiveOperationException {
        CraftEngineItems.class.getMethod("byId", String.class);
        CraftEngineItems.class.getMethod("getCustomItemId", ItemStack.class);
        net.momirealms.craftengine.core.block.setting.BlockSettings.class.getMethod("itemId");
        net.momirealms.craftengine.core.pack.Pack.class.getMethod("resourcePackFolders");
        net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent.class.getMethod("cacheData");
        AbstractPackManager.class.getField("PRESET_MODELS_BLOCK");
        AbstractPackManager.class.getField("PRESET_MODERN_MODELS_ITEM");
        AbstractPackManager.class.getField("PRESET_LEGACY_MODELS_ITEM");
    }
    public boolean enabled() { return active; }
    public ItemStack buildItem(String id) {
        var definition = CraftEngineItems.byId(id);
        return definition == null ? null : definition.buildBukkitItem();
    }
    public boolean matches(ItemStack stack, String id) {
        if (RuntimeCompat.empty(stack)) return false;
        Key key = CraftEngineItems.getCustomItemId(stack);
        return key != null && key.asString().equals(id);
    }
    public void register() {
        listener = new com.wenkrang.famara.loader.LoadCraftEngine();
        Bukkit.getPluginManager().registerEvents(listener, com.wenkrang.famara.Famara.getPlugin());
    }
    public boolean install() { return com.wenkrang.famara.loader.LoadCraftEngine.install(); }
    public void ensureLoaded() { com.wenkrang.famara.loader.LoadCraftEngine.ensureLoaded(); }
    public void close() {
        active = false;
        if (listener != null) org.bukkit.event.HandlerList.unregisterAll(listener);
    }

    public void refreshResources(Path report) {
        List<Path> roots = new ArrayList<>();
        for (var pack : BukkitCraftEngine.instance().packManager().loadedPacks()) {
            if (pack.enabled()) roots.addAll(Arrays.asList(pack.resourcePackFolders()));
        }
        Collections.reverse(roots);
        Path generated = defaultPackZip().toPath().getParent();
        CeColorService.configure(roots, List.of(defaultPackZip().toPath(), generated.resolve("resourcepack"),
                generated.resolve("resource_pack")), CraftEngineBackend::presetModel);
        CeColorService.diagnostics(report);
    }

    /** CE 自身已包含的原版模板，只作为缺失父模型的末级来源，不另行下载客户端。 */
    private static byte[] presetModel(String path) {
        String prefix = "assets/minecraft/models/";
        if (!path.startsWith(prefix) || !path.endsWith(".json")) return null;
        Key key = Key.of("minecraft:" + path.substring(prefix.length(), path.length() - 5));
        for (var presets : List.of(AbstractPackManager.PRESET_MODELS_BLOCK,
                AbstractPackManager.PRESET_MODERN_MODELS_ITEM, AbstractPackManager.PRESET_LEGACY_MODELS_ITEM)) {
            var json = presets.get(key);
            if (json != null) return json.toString().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    public BlockColor block(BlockData data, Session session) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(data);
        if (state == null || state.isEmpty()) return new BlockColor(NOT_CUSTOM, null);
        String id = state.owner().value().id().asString();
        BlockStateWrapper visual = state.visualBlockState();
        Key linked = state.settings().itemId();
        MapColor mapColor = state.settings().mapColor();
        Integer fallback = mapColor == null || mapColor == MapColor.CLEAR ? null : mapColor.color & 0xFFFFFF;
        return CeCompat.resolvePalette(id, visual == null ? null : visual.getAsString(),
                linked == null ? id : linked.asString(), fallback, session);
    }

    public EntityShape capture(Entity entity, Session session, Set<UUID> furnitureSeen) {
        if (CraftEngineFurniture.isCollisionEntity(entity) || CraftEngineFurniture.isSeat(entity)) return null;
        if (CraftEngineFurniture.isFurniture(entity)) {
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture == null || !furniture.isValid() || !furnitureSeen.add(furniture.uuid())) return null;
            return furnitureShape(furniture, session);
        }
        return itemShape(entity, session);
    }

    private static EntityShape furnitureShape(BukkitFurniture furniture, Session session) {
        List<double[]> boxes = new ArrayList<>();
        for (FurnitureHitBox hitBox : furniture.hitboxes())
            for (int i = 0; i < hitBox.partCount(); i++) addBox(boxes, hitBox.part(i).aabb());
        if (boxes.isEmpty()) {
            CullingData culling = furniture.cullingData();
            if (culling != null) addBox(boxes, culling.aabb());
        }
        if (boxes.isEmpty()) {
            LOG.warning("CE 家具没有可用的碰撞/剔除盒，无法拍摄：" + furniture.id());
            return null;
        }
        String id = furniture.id().asString();
        Integer rgb = session.palette.configured(id);
        if (rgb == null) {
            FurnitureVariant variant = furniture.currentVariant();
            if (variant != null) for (FurnitureElementConfig<?> element : variant.elementConfigs()) {
                Key itemId = null;
                if (element instanceof ItemDisplayFurnitureElementConfig c) itemId = c.itemId;
                else if (element instanceof ItemFurnitureElementConfig c) itemId = c.itemId;
                if (itemId != null) rgb = definitionColor(itemId.asString(), session);
                if (rgb != null) break;
            }
        }
        if (rgb == null) rgb = definitionColor(id, session);
        return new EntityShape(boxes, rgb == null ? null : rgbToColor(rgb), id);
    }

    private static Integer definitionColor(String id, Session session) {
        return session.definitions.computeIfAbsent(id, key -> {
            Integer manual = session.palette.configured(key);
            if (manual != null) return Optional.of(manual);
            var definition = CraftEngineItems.byId(key);
            if (definition != null) return Optional.ofNullable(itemColor(definition.buildBukkitItem(), key, session, "fixed"));
            return Optional.ofNullable(session.textures.itemColor(key, PackColorIndex.ItemState.EMPTY));
        }).orElse(null);
    }

    public void refreshItemModels() {
        Map<String, ItemModel> next = new HashMap<>();
        for (Key id : CraftEngineItems.loadedItems().keySet()) {
            try {
                var definition = CraftEngineItems.byId(id);
                if (definition != null) next.put(id.asString(), describeItem(definition.buildBukkitItem(), id.asString(), "fixed"));
            } catch (RuntimeException e) { LOG.warning("无法读取 CE 物品模型：" + id + "：" + e.getMessage()); }
        }
        CeColorService.models(next);
    }

    private static Integer itemColor(ItemStack stack, String id, Session session, String context) {
        Integer manual = session.palette.configured(id);
        if (manual != null) return manual;
        ItemModel model = describeItem(stack, id, context);
        Integer rgb = session.textures.itemColor(model.resource(), model.state());
        if (rgb != null || (RuntimeCompat.modernItemModels() && session.textures.items().containsKey(model.resource()))) return rgb;
        PackColorIndex.ItemState state = model.state();
        if (!RuntimeCompat.modernItemModels()) {
            if (id.equals(model.resource())) return null;
            state = new PackColorIndex.ItemState(state.flags(), state.strings(), state.floats(),
                    state.displayContext(), state.damage(), state.count());
        }
        return session.textures.itemColor(id, state);
    }

    private static ItemModel describeItem(ItemStack stack, String id, String context) {
        ItemStack client = stack;
        try { client = BukkitItemManager.instance().s2c(stack.clone(), null).orElse(stack); }
        catch (RuntimeException e) { LOG.warning("CE 客户端物品组件转换失败，尝试现有模型组件：" + id + "：" + e.getMessage()); }
        ItemMeta meta = client.getItemMeta();
        RuntimeCompat.ModelData data = RuntimeCompat.modelData(meta);
        String model = !RuntimeCompat.modernItemModels() && data.legacy() != null
                ? client.getType().getKey().toString() : data.itemModel();
        if (model == null) {
            var wrapped = BukkitItemManager.instance().wrap(client);
            model = wrapped.itemModel().orElse(null);
        }
        // 旧版 CE 把整数 CMD 写入原版载体的 overrides，不能只查同名自定义物品。
        if (model == null) model = RuntimeCompat.modernItemModels() ? id : client.getType().getKey().toString();
        float damage = meta instanceof Damageable d ? (float) d.getDamage() / Math.max(1, client.getType().getMaxDurability()) : 0;
        PackColorIndex.ItemState state = new PackColorIndex.ItemState(data.flags(), data.strings(), data.floats(),
                context, damage, client.getAmount(), RuntimeCompat.modernItemModels() ? null : data.legacy());
        return new ItemModel(model, state);
    }

    public static EntityShape itemShape(Entity entity, Session session) {
        ItemStack stack;
        String context;
        if (entity instanceof Item item) { stack = item.getItemStack(); context = "ground"; }
        else if (entity instanceof ItemDisplay display) {
            stack = display.getItemStack();
            context = display.getItemDisplayTransform().name().toLowerCase(Locale.ROOT);
        } else if (entity instanceof ItemFrame frame) { stack = frame.getItem(); context = "fixed"; }
        else return null;
        if (RuntimeCompat.empty(stack)) return null;
        Key key = CraftEngineItems.getCustomItemId(stack);
        if (key == null) return null;
        String id = key.asString();
        Integer rgb = itemColor(stack, id, session, context);
        double[] box = itemBox(entity);
        return box == null ? null : new EntityShape(Collections.singletonList(box), rgb == null ? null : rgbToColor(rgb), id);
    }

    /** 可见尺寸近似，不依赖 ItemDisplay 可能为零的碰撞包围盒。 */
    private static double[] itemBox(Entity entity) {
        Location location = entity.getLocation();
        Matrix4f transform = new Matrix4f();
        float hx = .25f, hy = .25f, hz = .25f;
        if (entity instanceof ItemDisplay display) {
            var t = display.getTransformation();
            transform.rotateY((float) Math.toRadians(-location.getYaw()))
                    .rotateX((float) Math.toRadians(location.getPitch()))
                    .translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale()).rotate(t.getRightRotation());
            hx = hy = hz = .5f;
        } else if (entity instanceof ItemFrame frame) {
            var facing = frame.getFacing();
            hx = facing.getModX() != 0 ? .025f : .375f;
            hy = facing.getModY() != 0 ? .025f : .375f;
            hz = facing.getModZ() != 0 ? .025f : .375f;
        }
        double[] box = transformedBox(transform, hx, hy, hz);
        if (box != null) {
            box[0] += location.getX(); box[3] += location.getX();
            box[1] += location.getY(); box[4] += location.getY();
            box[2] += location.getZ(); box[5] += location.getZ();
        }
        return box;
    }

    private static void addBox(List<double[]> boxes, AABB aabb) {
        if (aabb != null) boxes.add(new double[]{aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ});
    }
}
