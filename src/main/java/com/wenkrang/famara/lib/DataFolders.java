package com.wenkrang.famara.lib;

import org.bukkit.plugin.Plugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 改名只改变插件显示身份；自动沿用唯一旧数据目录，不搬动或合并消费账本。 */
public final class DataFolders {
    private DataFolders() {}
    private static final Map<Plugin,File> selected=new WeakHashMap<>();
    public static synchronized File of(Plugin plugin){File existing=selected.get(plugin);if(existing!=null)return existing;try{File folder=select(plugin.getDataFolder().toPath()).toFile();selected.put(plugin,folder);return folder;}catch(IOException error){throw new IllegalStateException("FamaraV2数据目录选择失败，未新建空账本",error);}}
    public static Path select(Path preferred)throws IOException {
        Path target=preferred.toAbsolutePath().normalize();if(target.getFileName()==null || !target.getFileName().toString().equalsIgnoreCase("FamaraV2"))return preferred;
        if(Files.isSymbolicLink(target))throw new IOException("拒绝符号链接插件数据目录："+target);
        if(meaningful(target))return target;List<Path> candidates=new ArrayList<>();
        for(String old:List.of("Famarareload","Famara")) {Path source=target.resolveSibling(old);if(Files.isSymbolicLink(source))throw new IOException("拒绝符号链接旧数据目录："+source);if(meaningful(source))candidates.add(source);}
        if(candidates.size()>1)throw new IOException("发现多个旧数据目录，未擅自合并照片或账本："+candidates);
        return candidates.isEmpty()?target:candidates.getFirst();
    }
    private static boolean meaningful(Path directory)throws IOException {
        if(!Files.exists(directory))return false;if(!Files.isDirectory(directory))throw new IOException("插件数据路径不是目录："+directory);
        try(var entries=Files.list(directory)){return entries.anyMatch(path->!path.getFileName().toString().equalsIgnoreCase("clazz"));}
    }
}
