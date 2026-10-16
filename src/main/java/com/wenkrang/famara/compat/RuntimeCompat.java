package com.wenkrang.famara.compat;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/** 缓存跨版本可选能力；公共签名只使用 1.20 已存在的 Bukkit 类型。 */
public final class RuntimeCompat {
    private static final NamespacedKey VIEWFINDER = new NamespacedKey("famara", "viewfinder_open");
    public static final NamespacedKey ZOOM_KEY = new NamespacedKey("famara", "viewfinder_zoom");
    public static final UUID ZOOM_UUID = UUID.nameUUIDFromBytes("famara:viewfinder_zoom".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final Method COMPONENT_GET = method(ItemMeta.class, "getCustomModelDataComponent");
    private static final Class<?> COMPONENT = COMPONENT_GET == null ? null : COMPONENT_GET.getReturnType();
    private static final Method FLAGS_GET = method(COMPONENT, "getFlags");
    private static final Method FLAGS_SET = method(COMPONENT, "setFlags", List.class);
    private static final Method FLOATS_GET = method(COMPONENT, "getFloats");
    private static final Method STRINGS_GET = method(COMPONENT, "getStrings");
    private static final Method COMPONENT_SET = COMPONENT == null ? null : method(ItemMeta.class, "setCustomModelDataComponent", COMPONENT);
    private static final Method ITEM_MODEL = method(ItemMeta.class, "getItemModel");
    private static final Method ITEM_MODEL_SET = method(ItemMeta.class, "setItemModel", NamespacedKey.class);
    private static final Method MAP_COLOR = method(BlockData.class, "getMapColor");
    private static final Method SKY_LIGHT = firstMethod(World.class, "hasSkyLight", "hasSkylight");
    private static final Method FOUR_ARGUMENT_SNAPSHOT = method(Chunk.class, "getChunkSnapshot", boolean.class, boolean.class, boolean.class, boolean.class);
    private static final Method MODIFIER_KEY = method(AttributeModifier.class, "getKey");
    private static final Constructor<AttributeModifier> KEY_MODIFIER = constructor(AttributeModifier.class,
            NamespacedKey.class, double.class, AttributeModifier.Operation.class);
    private static final Attribute MOVEMENT = movementAttribute();
    private RuntimeCompat() {}

    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        if (type == null) return null;
        try { return type.getMethod(name, arguments); }
        catch (NoSuchMethodException e) { return null; }
    }
    private static Method firstMethod(Class<?> type, String... names) {
        for (String name : names) {
            Method result = method(type, name);
            if (result != null) return result;
        }
        return null;
    }
    private static <T> Constructor<T> constructor(Class<T> type, Class<?>... arguments) {
        try { return type.getConstructor(arguments); }
        catch (NoSuchMethodException e) { return null; }
    }
    private static Object invoke(Method method, Object target, Object... arguments) {
        if (method == null) return null;
        try { return method.invoke(target, arguments); }
        catch (IllegalAccessException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            throw new IllegalStateException("兼容接口调用失败：" + method.toGenericString(), cause);
        }
    }
    public static boolean modernItemModels() { return FLAGS_GET != null && FLAGS_SET != null && COMPONENT_SET != null && ITEM_MODEL != null && ITEM_MODEL_SET != null; }
    public static void itemModel(ItemMeta meta, NamespacedKey model) {
        if (modernItemModels() && ITEM_MODEL_SET != null) invoke(ITEM_MODEL_SET, meta, model);
    }
    public static boolean hasMapColor() { return MAP_COLOR != null; }
    public static boolean hasSkyLight(World world) {
        if (SKY_LIGHT == null) throw new IllegalStateException("服务端缺少世界天光查询能力");
        return Boolean.TRUE.equals(invoke(SKY_LIGHT, world));
    }
    public static String snapshotMode() { return FOUR_ARGUMENT_SNAPSHOT == null ? "三参数（含旧版快照光照数据）" : "四参数（显式包含光照）"; }
    public static String modifierMode() { return KEY_MODIFIER == null ? "UUID" : "NamespacedKey"; }
    public static boolean empty(ItemStack stack) { return stack == null || stack.getAmount() <= 0 || stack.getType().isAir(); }

    private static Object component(ItemMeta meta) { return COMPONENT_GET == null ? null : invoke(COMPONENT_GET, meta); }
    @SuppressWarnings("unchecked")
    private static <T> List<T> values(Method getter, Object component) {
        if (getter == null || component == null) return List.of();
        Object value = invoke(getter, component);
        return value instanceof List<?> list ? List.copyOf((List<T>) list) : List.of();
    }
    public record ModelData(String itemModel, List<Boolean> flags, List<String> strings, List<Float> floats, Integer legacy) {}
    public static ModelData modelData(ItemMeta meta) {
        if (meta == null) return new ModelData(null, List.of(), List.of(), List.of(), null);
        Object component = component(meta);
        Object key = ITEM_MODEL == null ? null : invoke(ITEM_MODEL, meta);
        List<Float> floats = values(FLOATS_GET, component);
        Integer legacy = null;
        if (COMPONENT_GET == null && meta.hasCustomModelData()) legacy = meta.getCustomModelData();
        else if (!floats.isEmpty()) legacy = floats.get(0).intValue();
        if (floats.isEmpty() && legacy != null) floats = List.of(legacy.floatValue());
        return new ModelData(key == null ? null : key.toString(), values(FLAGS_GET, component),
                values(STRINGS_GET, component), floats, legacy);
    }
    public static boolean viewfinder(ItemMeta meta) {
        if (meta == null) return false;
        Byte saved = meta.getPersistentDataContainer().get(VIEWFINDER, PersistentDataType.BYTE);
        if (saved != null) return saved != 0;
        List<Boolean> flags = values(FLAGS_GET, component(meta));
        return !flags.isEmpty() && Boolean.TRUE.equals(flags.get(0));
    }
    public static ItemMeta viewfinder(ItemMeta meta, boolean open) {
        meta.getPersistentDataContainer().set(VIEWFINDER, PersistentDataType.BYTE, (byte) (open ? 1 : 0));
        if (modernItemModels()) {
            Object component = component(meta);
            List<Boolean> flags = new ArrayList<>(RuntimeCompat.<Boolean>values(FLAGS_GET, component));
            if (flags.isEmpty()) flags.add(open); else flags.set(0, open);
            invoke(FLAGS_SET, component, flags);
            invoke(COMPONENT_SET, meta, component);
        }
        // 旧版整数 CMD 属于 CE 模型分配，不拿它保存开关，防止损坏 CE 物品身份/外观。
        return meta;
    }
    public static ChunkSnapshot snapshot(Chunk chunk) {
        return FOUR_ARGUMENT_SNAPSHOT == null ? chunk.getChunkSnapshot(false, false, false)
                : (ChunkSnapshot) invoke(FOUR_ARGUMENT_SNAPSHOT, chunk, false, false, false, true);
    }
    public static Integer mapColor(BlockData state) {
        if (state == null || MAP_COLOR == null) return null;
        Object value = invoke(MAP_COLOR, state);
        return value instanceof Color color ? color.asRGB() : null;
    }
    private static Attribute movementAttribute() {
        for (String name : List.of("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED")) {
            try { return (Attribute) Attribute.class.getField(name).get(null); }
            catch (ReflectiveOperationException ignored) { }
        }
        throw new IllegalStateException("服务端缺少移动速度属性，无法初始化相机缩放");
    }
    public static org.bukkit.potion.PotionEffectType slowness() {
        org.bukkit.potion.PotionEffectType type = org.bukkit.potion.PotionEffectType.getByName("SLOWNESS");
        if (type == null) type = org.bukkit.potion.PotionEffectType.getByName("SLOW");
        if (type == null) throw new IllegalStateException("服务端缺少缓慢药水类型");
        return type;
    }
    public static Attribute movementAttributeType() { return MOVEMENT; }
    public static AttributeModifier ownModifier(AttributeInstance attribute) {
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (MODIFIER_KEY != null) {
                Object key = invoke(MODIFIER_KEY, modifier);
                if (ZOOM_KEY.equals(key)) return modifier;
                // 新版不对无关 NamespacedKey 调用已废弃的 UUID 转换；兼容旧 UUID 数据升级。
                if (key instanceof NamespacedKey namespaced && namespaced.getKey().equals(ZOOM_UUID.toString()))
                    return modifier;
            } else if (ZOOM_UUID.equals(modifier.getUniqueId())) return modifier;
        }
        return null;
    }
    public static AttributeModifier zoomModifier(double amount) {
        if (KEY_MODIFIER != null) {
            try { return KEY_MODIFIER.newInstance(ZOOM_KEY, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("构造相机缩放修饰器失败", e); }
        }
        return new AttributeModifier(ZOOM_UUID, "famara:viewfinder_zoom", amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
    }
}
