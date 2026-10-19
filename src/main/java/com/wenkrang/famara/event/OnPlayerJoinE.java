package com.wenkrang.famara.event;

import com.wenkrang.famara.itemSystem.ItemSystem;
import com.wenkrang.famara.lib.ItemUtils;
import com.wenkrang.famara.lib.Sched;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.logging.Logger;

import static com.wenkrang.famara.Famara.plugin;
import static com.wenkrang.famara.Famara.renderService;
import static com.wenkrang.famara.event.OnUseCameraE.getId;

public class OnPlayerJoinE implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        //进行相关检测
        startCheck(event.getPlayer());
    }

    /**
     * 开始对玩家的周期检测，可在任意线程调用
     * 检测任务运行在玩家所在的区域线程上，玩家下线后自动停止
     *
     * @param player 玩家
     */
    private static void updateCamera(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        ItemStack normalized = ItemUtils.normalizeCamera(held);
        if (normalized != held) {
            player.getInventory().setItemInMainHand(normalized);
            held = normalized;
        }
        boolean camera = ItemUtils.isCamera(held);
        boolean viewing = camera && ItemUtils.checkName(held, "§f相机") && ItemUtils.isViewfinderOpen(held.getItemMeta());
        if (viewing && player.isSneaking()) {
            held.setItemMeta(ItemUtils.setViewfinderOpen(held.getItemMeta(), false));
            player.getInventory().setItemInMainHand(held);
            player.getWorld().playSound(player.getLocation(), "famara:famara.viewfinder", 1, 1);
            viewing = false;
        }
        com.wenkrang.famara.lib.ViewfinderZoom.update(player, viewing, camera);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        com.wenkrang.famara.lib.ViewfinderZoom.clear(event.getPlayer());
    }

    public static void startCheck(Player player) {
        BossBar progress = Bukkit.createBossBar("冲洗进度", BarColor.WHITE, BarStyle.SOLID);
        File PlayerFile = new File(com.wenkrang.famara.lib.DataFolders.of(plugin), "players/" + player.getName() + ".yml");

        if(!PlayerFile.exists()) {
            try {
                if (!PlayerFile.createNewFile()) {
                    Logger.getGlobal().warning("Failed to create PlayerFile");
                }
            }catch (Exception ignore){
            }
            Sched.entity(player, () -> player.getInventory().addItem(ItemSystem.get("recipeBook")));
        }

        Sched.entityTimer(player, task -> {
            if (!player.isOnline()) {
                progress.removeAll();
                task.cancel();
                return;
            }
            // 缩放与收镜清理必须先于冲洗进度的提前返回执行。
            try{updateCamera(player);}catch(RuntimeException error){progress.removeAll();return;}
            var v2=com.wenkrang.famara.render.platform.PhotoRuntime.progress(player.getInventory().getItemInMainHand());
            if(v2.tracked()) {
                if(v2.success() || v2.failed()){progress.removeAll();return;}
                progress.addPlayer(player);progress.setProgress(v2.pixels()/16384.0);progress.setTitle("V2冲洗 "+v2.message());return;
            }
            if (player.getInventory().getItemInMainHand().getItemMeta() != null) {
                if (player.getInventory().getItemInMainHand()
                        .getItemMeta()
                        .getDisplayName()
                        .equalsIgnoreCase("§f照片（按右键撕开拉片）")) {
                    int id = getId(player.getInventory().getItemInMainHand(), 3);

                    if (!renderService.progress.containsKey(String.valueOf(id))) {
                        progress.removeAll();
                        return;
                    }
                    Integer i = renderService.progress.get(String.valueOf(id));

                    if (i != 16384) progress.addPlayer(player);

                    try {
                        progress.setProgress((double) i / 16384);
                        if (renderService.renderRealSpeeds.get(String.valueOf(id)) == null) return;
                        progress.setTitle("冲洗进度 "
                                + Math.round((double) i / 16384 * 100) + "% "
                                + "s:" + renderService.renderRealSpeeds.get(String.valueOf(id)) + "p/s "
                                + "ETA:" + (16384 - renderService.progress.get(String.valueOf(id))) /
                                Math.max(1, renderService.renderRealSpeeds.get(String.valueOf(id))) + "s");

                    } catch (Exception e) {
                        progress.removeAll();
                        renderService.progress.remove(String.valueOf(id));
                    }

                    if (i == 16384) {
                        progress.removeAll();
                    }
                }else {
                    progress.removeAll();
                }
            }else {
                progress.removeAll();
            }
        }, 1, 3);
    }
}
