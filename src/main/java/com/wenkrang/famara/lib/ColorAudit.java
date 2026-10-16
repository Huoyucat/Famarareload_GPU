package com.wenkrang.famara.lib;

import com.wenkrang.famara.Famara;
import com.wenkrang.famara.render.stage.RayTraceStage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** 全量颜色审计；诊断数据不受物品形态或 GUI 容量限制。 */
public final class ColorAudit {
    private static volatile Report report = new Report(List.of(), List.of(), List.of(), 0);
    public record Report(List<String> fallback, List<String> unresolved, List<String> invalid, int total) {
        public Report {
            fallback = List.copyOf(fallback);
            unresolved = List.copyOf(unresolved);
            invalid = List.copyOf(invalid);
        }
    }
    private ColorAudit() {}

    /** 注册表就绪后的全局线程调用。 */
    public static void refresh() {
        ColorManager.View palette = ColorManager.view();
        List<String> fallback = new ArrayList<>(), unresolved = new ArrayList<>(), missing = new ArrayList<>();
        int total = 0;
        for (Material material : Material.values()) {
            if (material.isLegacy() || !RayTraceStage.isBlock(material)) continue;
            total++;
            if (palette.configured(material.name()) != null) continue;
            missing.add(material.name());
            if (palette.vanilla(material.name()) == null) unresolved.add(material.name());
            else fallback.add(material.name());
        }
        missing.sort(String::compareTo);
        report = new Report(fallback, unresolved, palette.issues(), total);
        ColorManager.excludingBlocks.clear();
        ColorManager.excludingBlocks.addAll(missing);
        Famara.excludingBlocksInv.clear();
        for (int i = 0; i < Math.min(54, missing.size()); i++) {
            String name = missing.get(i);
            Material material = Material.valueOf(name);
            ItemStack item = new ItemStack(material.isItem() ? material : Material.PAPER);
            var meta = item.getItemMeta();
            meta.setDisplayName(name);
            meta.setLore(List.of(palette.vanilla(name) == null ? "尚无可用颜色" : "已使用原版地图色，可补充手动颜色",
                    "完整清单：/fa color <页码>"));
            item.setItemMeta(meta);
            Famara.excludingBlocksInv.setItem(i, item);
        }
        Report snapshot = report;
        String text = "可渲染方块：" + total + "\n使用原版地图色：" + fallback.size()
                + "\n仍无颜色：" + unresolved.size() + "\n无效配置：" + palette.issues().size()
                + "\n\n地图色回退：\n" + String.join("\n", fallback)
                + "\n\n仍无颜色：\n" + String.join("\n", unresolved)
                + "\n\n无效配置：\n" + String.join("\n", snapshot.invalid);
        Sched.async(() -> {
            try { Files.writeString(Famara.dataFolder.toPath().resolve("color-audit.txt"), text, StandardCharsets.UTF_8); }
            catch (Exception e) { Logger.getLogger("Famara").warning("写入颜色审计失败：" + e.getMessage()); }
        });
    }

    public static void describe(CommandSender sender, int requestedPage) {
        Report snapshot = report;
        List<String> names = new ArrayList<>(snapshot.unresolved);
        names.addAll(snapshot.fallback);
        int pages = Math.max(1, (names.size() + 11) / 12);
        int page = Math.max(1, Math.min(pages, requestedPage));
        sender.sendMessage("方块 " + snapshot.total + "；地图色回退 " + snapshot.fallback.size()
                + "；仍无颜色 " + snapshot.unresolved.size() + "；无效配置 " + snapshot.invalid.size());
        sender.sendMessage("待补充显式颜色 " + page + "/" + pages + "，地图色回退不属于渲染失败：");
        names.stream().skip((long) (page - 1) * 12).limit(12).forEach(sender::sendMessage);
        sender.sendMessage("完整报告：plugins/Famarareload/color-audit.txt；下一页：/fa color " + Math.min(pages, page + 1));
    }
}
