package com.wenkrang.famara.event;

import com.wenkrang.famara.lib.CeItems;
import com.wenkrang.famara.lib.ItemUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class OnLoadFilm implements Listener {

    @EventHandler
    public void LoadFilm(PlayerSwapHandItemsEvent event) {
        // 按 F 时主手的物品就是要装填的相机，从 CraftEngine 菜单获取的相机先补全名称与描述
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        ItemStack camera;
        try{camera=ItemUtils.normalizeCamera(held);}catch(RuntimeException error){event.setCancelled(true);event.getPlayer().sendMessage("§c相机账本未就绪，未消耗胶卷盒："+error.getMessage());return;}
        if (camera != held) event.getPlayer().getInventory().setItemInMainHand(camera);
        if (camera.getItemMeta() != null) {
            // 只有取景器关闭的相机才能装填胶卷
            if (ItemUtils.isCamera(camera) && !ItemUtils.isViewfinderOpen(camera.getItemMeta())) {
                if (ItemUtils.getFilmAmount(camera) != 0) return;
                if(com.wenkrang.famara.render.platform.CameraIdentity.request(camera.getItemMeta()).isPresent()) {
                    event.setCancelled(true);event.getPlayer().sendMessage("§c请先处理当前V2撕拉片，再装填新胶卷");return;
                }
                PlayerInventory inventory = event.getPlayer().getInventory();
                for (int i = 0;i < inventory.getSize();i++) {
                    ItemStack item = inventory.getItem(i);
                    if (CeItems.is(item, CeItems.FILM_BOX)) {
                        if (item.getAmount() > 1) {
                            item.setAmount(item.getAmount() - 1);
                            inventory.setItem(i, item);
                        }else {
                            inventory.setItem(i, null);
                        }
                        event.getPlayer().getWorld().playSound(event.getPlayer().getLocation(), "famara:famara.attach.film.box", 1, 1);
                        ItemUtils.setCamera(event.getPlayer());
                        event.setCancelled(true);
                        // 一次只消耗一盒胶卷
                        return;
                    }
                }
            }
        }
    }
}
