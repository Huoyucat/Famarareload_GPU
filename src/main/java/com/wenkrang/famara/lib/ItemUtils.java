package com.wenkrang.famara.lib;

import com.wenkrang.famara.itemSystem.ItemSystem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.wenkrang.famara.compat.RuntimeCompat;

import java.util.List;

public class ItemUtils {
    public static boolean checkName(ItemStack itemStack, String s) {
        if (itemStack == null || itemStack.getItemMeta() == null) return false;
        return itemStack.getItemMeta().getDisplayName().equalsIgnoreCase(s);
    }

    /**
     * 判断物品是否为相机（包括已拍摄的相机）
     *
     * @param itemStack 要检查的物品
     * @return 检查结果
     */
    public static boolean isCamera(ItemStack itemStack) {
        return CeItems.is(itemStack, CeItems.CAMERA);
    }

    public static void setCamera(Player player) {
        ItemStack itemInMainHand = player.getInventory().getItemInMainHand();
        if (itemInMainHand.getItemMeta() != null) {
            ItemStack itemStack = setFilmAmount(itemInMainHand, 16);
            player.getInventory().setItemInMainHand(itemStack);
        }
    }

    /**
     * 补全相机的名称与描述
     * 从 CraftEngine 物品菜单或 /ce item give 获取的相机只有 CE 的基础数据，
     * 没有插件用来记录状态的名称和描述，这里替换为插件的标准相机并保留取景器状态
     *
     * @param itemStack 物品
     * @return 补全后的物品；不需要补全时返回原物品
     */
    public static ItemStack normalizeCamera(ItemStack itemStack) {
        if (!isCamera(itemStack)) return itemStack;
        ItemMeta meta = itemStack.getItemMeta();
        List<String> lore = meta.getLore();
        if (lore != null && lore.size() >= 3) return itemStack;

        ItemStack standard = ItemSystem.getOrNull("camera");
        if (standard == null) return itemStack;
        standard.setAmount(itemStack.getAmount());
        ItemMeta standardMeta = setViewfinderOpen(standard.getItemMeta(), isViewfinderOpen(meta));
        standard.setItemMeta(standardMeta);
        return standard;
    }

    public static int getFilmAmount(ItemStack itemStack) {
        List<String> lore = itemStack.getItemMeta().getLore();
        if (lore == null || lore.size() < 3) return 0;
        String s = lore.get(2);
        if (s.equalsIgnoreCase("§7未装填胶卷")) return 0;
        return Integer.parseInt(s.replace("§7已装填彩色胶卷（16 / ", "").replace("）", ""));
    }

    public static ItemStack setFilmAmount(ItemStack itemStack, int i) {
        itemStack = normalizeCamera(itemStack);
        ItemMeta itemMeta = itemStack.getItemMeta();
        List<String> lore = itemMeta.getLore();
        if (i <= 0) {
            lore.set(2, "§7未装填胶卷");
        } else lore.set(2, "§7已装填彩色胶卷（16 / " + i + "）");
        itemMeta.setLore(lore);

        final var newItem = itemStack.clone();
        newItem.setItemMeta(itemMeta);
        return newItem;
    }

    /**
     * 判断相机取景器是否打开
     * 取景器状态存放在 custom_model_data 的第 0 个 flag 中，CraftEngine 据此切换开/关模型
     *
     * @param itemMeta 相机的ItemMeta
     * @return 取景器是否打开
     **/
    public static boolean isViewfinderOpen(ItemMeta itemMeta) {
        return RuntimeCompat.viewfinder(itemMeta);
    }

    /**
     * 设置相机取景器状态
     *
     * @param itemMeta 相机的ItemMeta
     * @param open 是否打开
     * @return 修改后的ItemMeta
     **/
    public static ItemMeta setViewfinderOpen(ItemMeta itemMeta, boolean open) {
        return RuntimeCompat.viewfinder(itemMeta, open);
    }
}
