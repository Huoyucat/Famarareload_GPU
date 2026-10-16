package com.wenkrang.famara.lib;

import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.compat.RuntimeCompat;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

/** 基础物品入口；CE 是可选外观/自定义内容增强，不是创建物品的前置。 */
public final class CeItems {
    public static final String CAMERA = "famara:camera";
    public static final String FILM_BOX = "famara:film_box";
    public static final String PHOTO = "famara:photo";
    public static final int CAMERA_MODEL = 900001;
    public static final int FILM_MODEL = 900002;
    public static final int PHOTO_MODEL = 900003;
    private static final NamespacedKey ITEM_ID = new NamespacedKey("famara", "item_id");
    private CeItems() {}

    private static boolean known(String id) { return CAMERA.equals(id) || FILM_BOX.equals(id) || PHOTO.equals(id); }

    @Nullable
    public static ItemStack build(String id) {
        if (!known(id)) return null;
        ItemStack provided = CeCompat.integrationItem(id);
        if (RuntimeCompat.empty(provided) || (PHOTO.equals(id) && provided.getType() != Material.FILLED_MAP)) provided = null;
        ItemStack item = provided == null ? standalone(id) : provided.clone();
        mark(item, id);
        return item;
    }

    private static ItemStack standalone(String id) {
        Material type = CAMERA.equals(id) ? Material.STICK : PHOTO.equals(id) ? Material.FILLED_MAP : Material.PAPER;
        int model = CAMERA.equals(id) ? CAMERA_MODEL : PHOTO.equals(id) ? PHOTO_MODEL : FILM_MODEL;
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(model);
        RuntimeCompat.itemModel(meta, NamespacedKey.fromString(id));
        item.setItemMeta(meta);
        return item;
    }

    private static void mark(ItemStack item, String id) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(ITEM_ID, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    /** 身份以 PDC 为准，不以名称、材质或 CMD 代替。CE 旧物品仅在原生校验成功后补标记。 */
    public static boolean is(@Nullable ItemStack item, String id) {
        if (!known(id) || RuntimeCompat.empty(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && id.equals(meta.getPersistentDataContainer().get(ITEM_ID, PersistentDataType.STRING))) return true;
        if (!CeCompat.integrationMatches(item, id)) return false;
        mark(item, id);
        return true;
    }
}
