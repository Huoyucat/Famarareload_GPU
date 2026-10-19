package com.wenkrang.famara.loader;

import com.wenkrang.famara.command.CmdArgs;
import com.wenkrang.famara.command.FaCmd;
import com.wenkrang.famara.itemSystem.RecipeBook;
import com.wenkrang.famara.lib.ColorManager;
import com.wenkrang.famara.lib.ColorAudit;
import com.wenkrang.famara.lib.ConsoleLogger;
import com.wenkrang.famara.lib.Sched;
import com.wenkrang.famara.lib.Translation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Objects;

import static com.wenkrang.famara.Famara.excludingBlocksInv;
import static com.wenkrang.famara.Famara.renderService;


public class LoadCmd {
    /**
     * 注册命令触发器至Spigot
     */
    public static void spigotRegister() {
        Objects.requireNonNull(Bukkit.getServer().getPluginCommand("fa"))
                .setExecutor(FaCmd.exec);
        Objects.requireNonNull(Bukkit.getServer().getPluginCommand("fa"))
                .setTabCompleter(FaCmd.tabCompleter);
    }

    public static void registerCommands() {

        FaCmd.register(new FaCmd("help", new CmdArgs[]{},
                (i, j) -> FaCmd.getHelp(i)));

        FaCmd.register(new FaCmd("color", new CmdArgs[]{new CmdArgs.IntArgument("页码", true)},
                (i, j) -> {
                    if (i.isOp()) ColorAudit.describe(i, j.isEmpty() ? 1 : Integer.parseInt(j.getFirst()));
                }));

        FaCmd.register(new FaCmd("inv", new CmdArgs[]{},
                (i, j) -> {if (i.isOp() && i instanceof Player player)
                    Sched.entity(player, () -> player.openInventory(excludingBlocksInv));}));

        FaCmd.register(new FaCmd(
                "set", new CmdArgs[]{
                new CmdArgs.WeakFixedArgument(ColorManager.excludingBlocks, false),
                new CmdArgs.IntArgument("RED", false),
                new CmdArgs.IntArgument("GREEN", false),
                new CmdArgs.IntArgument("BLUE", false)
        }, (i, j) -> {

            if (!i.isOp()) return;

            try {
                ColorManager.setColor(j.get(0), j.get(1),j.get(2),j.get(3));
            } catch (IllegalArgumentException e) {
                i.sendMessage(Translation.CURRENT.of("setError1"));
                return;
            }
            i.sendMessage(Translation.CURRENT.of("setSuccessfully"));
        }
        ));

        FaCmd.register(new FaCmd(
                "speed", new CmdArgs[]{
                new CmdArgs.IntArgument("speed", false)
        }, (i, j) -> {
            if (!i.isOp()) return;

            if(com.wenkrang.famara.render.platform.PhotoRuntime.textured()) {
                try{int actual=com.wenkrang.famara.render.platform.PhotoRuntime.pixelLimit(Integer.parseInt(j.getFirst()));i.sendMessage("§7V2每批像素上限已设为 "+actual+"；不提高配置批次或共享CPU预算。");}
                catch(RuntimeException error){i.sendMessage("§c未修改V2速度："+error.getMessage());}return;
            }
            renderService.speed = Integer.parseInt(j.getFirst());

            if (renderService.speed > 500)
                i.sendMessage(Translation.CURRENT.of("warning1"));

            i.sendMessage(String.format(
                    Translation.CURRENT.of("speedSetSuccessfully"), renderService.speed));
        }));

        FaCmd.register(new FaCmd(
                "resource", new CmdArgs[]{
        }, (i, j) -> {
            if (com.wenkrang.famara.compat.CeCompat.enabled()) {
                i.sendMessage(Translation.CURRENT.of("resourceByCraftEngine",
                        "§9§l[*]§r 资源包由 CraftEngine 分发，管理员可使用 /ce feature send-pack 重新发送"));
            } else {
                i.sendMessage("§9§l[*]§r 独立模式可选资源包：实际数据目录/" + com.wenkrang.famara.lib.StandalonePack.FILE_NAME);
                i.sendMessage("§7未加载只影响外观，拍照仍可用。管理员可将 ZIP 按普通服务端资源包托管/合并后分发。");
            }
        }));

        FaCmd.register(new FaCmd(
                "guide", new CmdArgs[]{}, (i, j) -> {
            if (i instanceof Player player) {
                // 命令可能不在玩家所在的区域线程执行，切换到玩家的调度器
                Sched.entity(player, () -> player.getWorld().dropItem(player.getLocation(), RecipeBook.RecipeBookItem.clone()));
            } else {
                i.sendMessage(Translation.CURRENT.of("useInGame"));
            }
        }));
        FaCmd.register(new FaCmd(
                        "step", new CmdArgs[]{
                        new CmdArgs.IntArgument("RED", false),
                        new CmdArgs.IntArgument("GREEN", false),
                        new CmdArgs.IntArgument("BLUE", false)
                }, (i, j) -> {
                    if (!i.isOp()) return;
                    try {
                        if (ColorManager.excludingBlocks.isEmpty()) { i.sendMessage("没有待补充的显式颜色"); return; }
                        String name = ColorManager.excludingBlocks.getFirst();
                        ColorManager.setColor(name, j.get(0), j.get(1), j.get(2));
                        i.sendMessage("已设置颜色：" + name);
                    }catch (Exception e) {
                        e.printStackTrace();
                    }
//                    }catch (NullPointerException e) {
//                        i.sendMessage(Translation.CURRENT.of("setError1"));
//                    }
                })
        );

        spigotRegister();

    }
}
