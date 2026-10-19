package com.wenkrang.famara.loader;

import com.wenkrang.famara.Famara;
import com.wenkrang.famara.compat.CeCompat;
import com.wenkrang.famara.compat.RuntimeCompat;
import com.wenkrang.famara.lib.CeItems;
import com.wenkrang.famara.lib.ConsoleLogger;
import com.wenkrang.famara.lib.Sched;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackGenerateEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 负责把插件内置的 CraftEngine 资源（物品配置、模型、贴图、音效）安装到 CraftEngine，
 * 并在 CraftEngine 加载完成后注册 Famara 的物品与配方
 */
public class LoadCraftEngine implements Listener {
    /** jar 内 CraftEngine 资源的路径前缀 */
    private static final String JAR_PREFIX = "craftengine/famara/";

    /** 是否已经因为资源缺失请求过 CraftEngine 重载，避免反复重载 */
    private static volatile boolean reloadRequested = false;

    /** 本次启动是否更新了 CraftEngine 资源，更新后需要重载 CraftEngine 才能生效 */
    private static volatile boolean resourcesChanged = false;

    /**
     * 将 jar 内的 CraftEngine 资源同步到 plugins/CraftEngine/resources/famara
     *
     * @return 是否有文件被新增或修改
     */
    public static boolean install() {
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null) {
            ConsoleLogger.error("未找到 CraftEngine，Famara 的物品将无法使用");
            return false;
        }
        File target = new File(craftEngine.getDataFolder(), "resources/famara");
        boolean changed = false;

        try (JarFile jar = new JarFile(getJarFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(JAR_PREFIX)) continue;

                File out = new File(target, entry.getName().substring(JAR_PREFIX.length()));
                byte[] data;
                try (InputStream in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (!RuntimeCompat.modernItemModels() && entry.getName().equals(JAR_PREFIX + "configuration/items.yml")) {
                    try (InputStream legacy = jar.getInputStream(jar.getJarEntry("compat/legacy-items.yml"))) {
                        data = legacy.readAllBytes();
                    }
                }
                // 内容一致则跳过，避免每次启动都触发重载
                if (out.isFile() && Arrays.equals(Files.readAllBytes(out.toPath()), data)) continue;

                Famara.mkdir(out.getParentFile());
                Files.write(out.toPath(), data);
                changed = true;
            }
        } catch (IOException | URISyntaxException e) {
            ConsoleLogger.error("CraftEngine 资源安装失败：" + e.getMessage());
            e.printStackTrace();
        }

        if (changed) ConsoleLogger.info("CraftEngine resources installed to " + target.getPath());
        resourcesChanged = changed;
        return changed;
    }

    /**
     * 获取插件 jar 文件
     * Famara 主类由热加载器加载，没有代码来源，因此通过 Bukkit 插件实例定位 jar
     */
    private static File getJarFile() throws URISyntaxException {
        return new File(Famara.getPlugin().getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * 检查 CraftEngine 物品是否就绪，就绪则加载物品与配方；
     * 若资源刚安装、CraftEngine 尚未读取到，则请求一次重载
     * 必须在全局区域线程调用
     */
    public static void ensureLoaded() {
        // 资源有更新（如插件升级新增了分类）时，即使物品已存在也要重载一次 CraftEngine
        if (resourcesChanged && !reloadRequested && !BukkitCraftEngine.instance().isReloading()) {
            resourcesChanged = false;
            reloadRequested = true;
            ConsoleLogger.info("Famara resources updated, reloading CraftEngine");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "craftengine reload all");
            return;
        }
        if (CeCompat.integrationItem(CeItems.CAMERA) != null) {
            LoadItem.loadItem();
            LoadRecipe.loadRecipe();
            CeCompat.refreshItemModels();
            return;
        }
        // CraftEngine 正在加载时等待其重载事件
        if (BukkitCraftEngine.instance().isReloading()) return;
        if (!reloadRequested) {
            reloadRequested = true;
            ConsoleLogger.info("Famara items not found in CraftEngine, reloading CraftEngine");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "craftengine reload all");
        } else {
            ConsoleLogger.error("CraftEngine 重载后仍找不到 Famara 物品，请检查 plugins/CraftEngine/resources/famara 是否完整");
        }
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        CeCompat.invalidateVisualResources();
        // 事件可能在异步线程触发，统一切回全局区域线程
        Sched.global(() -> {
            LoadCraftEngine.ensureLoaded();
            CeCompat.refreshResources(Famara.dataFolder.toPath().resolve("ce-color-index.txt"));
            long ticket = CeCompat.requestRebuild();
            CeCompat.scheduleRebuild(ticket);
        });
    }

    @EventHandler
    public void onResourcePackGenerated(AsyncResourcePackGenerateEvent event) {
        Path folder = event.resourcePackFolder();
        Path zip = event.zipFilePath();
        long ticket = CeCompat.generatedSources(folder, zip);
        Sched.global(() -> {
            // 旧版整数 CMD/新模型资源键可能在生成过程中重新分配，生成后更新组件映射。
            CeCompat.refreshItemModels();
            // refreshItemModels已请求最新索引；不再重复排同一代工作。
        });
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onResourcePackCached(net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent event) {
        CeCompat.externalSources(event.cacheData().externalFolders(), event.cacheData().externalZips());
        long ticket = CeCompat.requestRebuild();
        CeCompat.scheduleRebuild(ticket);
    }
}
