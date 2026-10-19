package com.wenkrang.famara.lib;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record Config(YamlConfiguration ref) {
    public static volatile Config CURRENT = new Config(null);
    public static volatile com.wenkrang.famara.render.platform.UnifiedRendererConfig.Result renderer;
    public static volatile String rendererFailure="";
    public static void initialize(org.bukkit.plugin.Plugin plugin) {
        renderer=null;rendererFailure="";
        if(!DataFolders.of(plugin).toPath().toAbsolutePath().normalize().equals(plugin.getDataFolder().toPath().toAbsolutePath().normalize()))plugin.getLogger().info("检测到旧版数据，继续沿用目录并自动迁移配置："+DataFolders.of(plugin)+"；照片、账本和原路径未搬动");
        try(var input=plugin.getResource("config.yml")) {
            if(input==null)throw new java.io.IOException("插件缺少默认config.yml");
            String defaults=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var result=com.wenkrang.famara.render.platform.UnifiedRendererConfig.load(com.wenkrang.famara.lib.DataFolders.of(plugin).toPath(),defaults);
            setCurrent(result.yaml());renderer=result;result.changes().forEach(plugin.getLogger()::info);
            plugin.getLogger().info("相机配置：config.yml，模式="+result.mode()+"，距离="+result.renderer().shot().distance()+"，快照MiB="+result.renderer().capture().maximumSnapshotBytes()/1048576+"，区块上限="+result.renderer().capture().maxChunksPerRequest()+"，实例="+result.renderer().preparation().maximumInstances());
        }catch(Exception error){rendererFailure=error.toString();plugin.getLogger().log(java.util.logging.Level.SEVERE,"相机配置加载/迁移失败，原文件保留、拍摄拒绝："+error,error);}
    }
    @NotNull
    public String of(@NotNull String key) {
        if (Objects.isNull(ref)) {
            return key;
        } else {
            return Objects.requireNonNullElse(ref.getString(key), key);
        }
    }

    public static void setCurrent(YamlConfiguration ref) {
        CURRENT = new Config(ref);
    }
}
