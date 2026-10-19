package com.wenkrang.famara;

import com.wenkrang.famara.event.*;
import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.itemSystem.BookPage;
import com.wenkrang.famara.itemSystem.RecipeBook;
import com.wenkrang.famara.lib.*;
import com.wenkrang.famara.loader.LoadCmd;
import com.wenkrang.famara.loader.LoadItem;
import com.wenkrang.famara.render.RenderService;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.bukkit.Bukkit.getServer;

/**
 * 主插件类，负责插件的初始化和关闭逻辑。
 * 包括命令注册、事件监听、资源加载、渲染器启动等。
 */
public final class Famara {

    /**
     * 当前颜色配置版本号。
     */
    public static int Version = 5;

    /**
     * 插件启用时调用的方法。
     * 负责初始化插件的各种功能模块，包括命令、事件、资源加载等。
     */

    public static Inventory excludingBlocksInv = Bukkit.createInventory(null, 54,"54");

    public static JavaPlugin plugin;

    public static RenderService renderService;

    /**
     * 插件数据文件夹路径。
     */
    public static File dataFolder;

    /**
     * 将jar内的文件解压至指定文件夹
     *
     * @param name 文件名
     * @param file 指定目录
     */
    public static void unpackFile(String name, File file) {
        try {
            Runnable runnable = () -> {
                try {
                    InputStream inputStream = Famara.getPlugin().getResource(name);
                    Files.copy(inputStream, file.toPath());
                    inputStream.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            };
            if (file.exists()) {
                YamlConfiguration tmp = YamlConfiguration.loadConfiguration(file);
                tmp.load(file);
                int version = tmp.getInt("version");
                if (Version > version) {
                    if (file.delete()) {
                        runnable.run();
                    }else {
                        Logger.getGlobal().warning(name + "文件更新失败，请尝试手动删除");
                    }
                }
            } else {
                runnable.run();
            }
        }catch (Exception e) {
            Logger.getGlobal().warning("§c§l[-] §r文件加载失败：" + name);
            e.printStackTrace();
        }

    }
    public static void mkdir(File file) {
        if (!file.exists()) {
            boolean mkdir = file.mkdirs();
            if (!mkdir) {
                Logger.getGlobal().warning("Failed to create directory");
            }
        }
    }

    public static JavaPlugin getPlugin() {
        try {
            // 使用完整类名
            Class<?> bootLoaderClass = Class.forName("com.wenkrang.famara.BootLoader");
            Method getPluginMethod = JavaPlugin.class.getMethod("getPlugin", Class.class);
            return (JavaPlugin) getPluginMethod.invoke(null, bootLoaderClass);
        } catch (Exception e) {
            // 备用方案
            JavaPlugin current=(JavaPlugin)Bukkit.getServer().getPluginManager().getPlugin("FamaraV2");return current!=null?current:(JavaPlugin)Bukkit.getServer().getPluginManager().getPlugin("Famarareload");
        }
    }

    public void onEnable() {
        //设置插件实例
        plugin = getPlugin();
        dataFolder = com.wenkrang.famara.lib.DataFolders.of(plugin);
        mkdir(dataFolder);
        CeCompat.initialize(plugin);
        Bukkit.getPluginManager().registerEvents(new com.wenkrang.famara.compat.CeLifecycle(),plugin);

        Sched.globalLater(() -> {
            unpackFile("language.yml", new File(dataFolder,"language.yml"));
            Translation.setCurrent(YamlConfiguration.loadConfiguration(new File(dataFolder,"language.yml")));
        }, 5);

        //加载配置
        // config升级只能按字段合并；不能沿用unpackFile的整文件删除逻辑。
        Config.initialize(plugin);

        //Bstats统计
        int pluginId = 27448;
        Metrics metrics = new Metrics(getPlugin(), pluginId);

        metrics.addCustomChart(new SimplePie("chart_id", () -> "My value"));

        ConsoleCommandSender consoleSender = getServer().getConsoleSender();
        consoleSender.sendMessage("    ____                                ");
        consoleSender.sendMessage("   / __/___ _____ ___  ____ __________ _");
        consoleSender.sendMessage("  / /_/ __ `/ __ `__ \\/ __ `/ ___/ __ `/");
        consoleSender.sendMessage(" / __/ /_/ / / / / / / /_/ / /  / /_/ / ");
        consoleSender.sendMessage("/_/  \\__,_/_/ /_/ /_/\\__,_/_/   \\__,_/  \n");
        ConsoleLogger.info("Server version: " + Bukkit.getMinecraftVersion());

        ConsoleLogger.info("Initializing recipe book");
        // 初始化配方书主页面，必须在加载物品前完成
        RecipeBook.mainPage = new BookPage("相机配方", new ConcurrentHashMap<>(), new HashMap<>(), new ConcurrentHashMap<>());
        LoadItem.loadRecipeBook();
        // 基础物品与配方始终可用，不等待可选 CE 的资源加载。
        LoadItem.loadItem();
        com.wenkrang.famara.loader.LoadRecipe.loadRecipe();

        ConsoleLogger.info("Registering commands");
        // 注册命令执行器和 completer
        LoadCmd.registerCommands();

        ConsoleLogger.info("Registering event listeners");
        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new BookClickE(), getPlugin());
        getServer().getPluginManager().registerEvents(new OnLoadFilm(), getPlugin());
        getServer().getPluginManager().registerEvents(new OnPlayerJoinE(), getPlugin());
        getServer().getPluginManager().registerEvents(new OnUseCameraE(), getPlugin());
        getServer().getPluginManager().registerEvents(new OpenBookE(), getPlugin());

        ConsoleLogger.info("Initializing photo storage directory");
        // 初始化照片存储目录
        dataFolder = com.wenkrang.famara.lib.DataFolders.of(plugin);

        mkdir(new File(com.wenkrang.famara.lib.DataFolders.of(plugin), "pictures"));

        mkdir(new File(com.wenkrang.famara.lib.DataFolders.of(plugin), "players"));

        mkdir(new File(com.wenkrang.famara.lib.DataFolders.of(plugin), "update"));
        // 加载颜色配置文件
        ColorManager.loadColor();
        com.wenkrang.famara.compat.CompatibilityReport.write(plugin);
        ColorManager.onChange(() -> {
            long ticket = CeCompat.requestRebuild();
            CeCompat.scheduleRebuild(ticket);
            Sched.global(ColorAudit::refresh);
        });

        ConsoleLogger.info("Installing CraftEngine resources");
        // 安装物品配置、模型、贴图与音效到 CraftEngine
        CeCompat.install();
        com.wenkrang.famara.lib.StandalonePack.export(plugin);

        ConsoleLogger.info("Starting renderer");
        // 启动渲染器
        renderService = new RenderService(com.wenkrang.famara.lib.DataFolders.of(plugin));
        com.wenkrang.famara.render.platform.PhotoRuntime.start(plugin);
        renderService.start();

        ConsoleLogger.info("Starting scheduled tasks");
        // 诊断包含无物品形态方块；不再远程整表覆盖用户配色。
        ColorAudit.refresh();

        // 完成照片渲染
        Sched.asyncTimer(() -> renderService.progress.forEach((id, progress) -> {
            if (progress >= 16384) renderService.progress.remove(id);
        }), 0, 20);

        ConsoleLogger.info("Loading items and recipes");
        // CraftEngine 物品就绪后加载物品与配方；CraftEngine 重载时会再次加载
        Sched.globalLater(CeCompat::ensureLoaded, 1);

        ConsoleLogger.info("Indexing CraftEngine pack textures for CE blocks/items");
        // 在异步线程构建 CE 资源包的贴图颜色索引
        CeCompat.refreshResources(dataFolder.toPath().resolve("ce-color-index.txt"));
        long initialIndex = CeCompat.requestRebuild();
        CeCompat.scheduleRebuild(initialIndex);

        // 对在线玩家执行加入检查
        getServer().getOnlinePlayers().forEach(OnPlayerJoinE::startCheck);

        ConsoleLogger.info("Loading complete, current version: " + plugin.getDescription().getVersion());
    }

    /**
     * 插件禁用时调用的方法。
     * 可用于执行清理工作。
     */
    public void onDisable() {
        com.wenkrang.famara.render.lib.LoadPhoto.close();
        com.wenkrang.famara.render.platform.PhotoRuntime.stop();
        CeCompat.close();
        Sched.cancelAll();
    }

    /** 从 CE 的 zip 或文件夹构建资源包贴图颜色索引，必须在异步线程调用 */
    public static void rebuildCePackIndex(long ticket) {
        CeCompat.rebuildLast(ticket);
    }
}
