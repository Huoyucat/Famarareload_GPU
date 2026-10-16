package com.wenkrang.famara.compat;

import com.wenkrang.famara.lib.Sched;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** 记录实际选择的分支，不将能力探测或编译成功包装成跨版本运行认证。 */
public final class CompatibilityReport {
    private CompatibilityReport() {}
    public static void write(Plugin plugin) {
        List<String> lines = new ArrayList<>();
        lines.add("Famara 多版本兼容能力报告（代码适配，不代表服务端实测通过）");
        lines.add("插件版本：" + plugin.getDescription().getVersion());
        lines.add("服务端：" + Bukkit.getName() + " / " + Bukkit.getVersion());
        lines.add("Bukkit：" + Bukkit.getBukkitVersion());
        lines.add("Java：" + System.getProperty("java.version") + "；插件最低要求 21，26.x 服务端要求 25");
        Plugin ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
        lines.add("CraftEngine（可选）：" + (ce == null ? "未安装" : ce.getDescription().getVersion()));
        lines.add("CE 兼容状态：" + CeCompat.status());
        lines.add("物品模型：" + (RuntimeCompat.modernItemModels() ? "现代 item_model + CMD flags；第一人称 empty" : "旧整数 CMD / PDC 开关；普通手持兼容模型"));
        lines.add("调度：" + Sched.mode());
        lines.add("快照：" + RuntimeCompat.snapshotMode());
        lines.add("属性修饰：" + RuntimeCompat.modifierMode());
        lines.add("地图色接口：" + (RuntimeCompat.hasMapColor() ? "实际 BlockData 地图色" : "贴图色表 + 动态语义回退；无法确定的项目继续诊断"));
        lines.add("目标：Paper/Folia 1.20.x–26.x 中实际存在的发行版；未来 26.x 需要重新核对");
        lines.add("当前构建 API：Paper 1.20.1；字节码：Java 21；可选 CE API 编译基线：26.9.2");
        lines.add(CeCompat.enabled() ? "使用 CE 生成包；未自动处理 ViaVersion 跨协议资源分发。"
                : "独立可选包：plugins/Famarareload/famara-standalone.zip；未加载仅显示原版外观。");
        try {
            Path output = plugin.getDataFolder().toPath().resolve("compatibility.txt");
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
            Logger.getLogger("Famara").info("兼容分支：" + Sched.mode() + "，物品模型="
                    + (RuntimeCompat.modernItemModels() ? "modern" : "legacy") + "；详情见 compatibility.txt");
        } catch (Exception e) { Logger.getLogger("Famara").warning("写入兼容报告失败：" + e.getMessage()); }
    }
}
