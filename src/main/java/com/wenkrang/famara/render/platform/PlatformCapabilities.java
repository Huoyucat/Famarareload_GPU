package com.wenkrang.famara.render.platform;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;

/** 账本可恢复后验证必要Paper API，不把版本字符串等同于平台能力。 */
public final class PlatformCapabilities {
    private PlatformCapabilities() {}
    public static void require() {
        require(World.class,"getChunkAtAsync",int.class,int.class,boolean.class);
        require(Chunk.class,"getChunkSnapshot",boolean.class,boolean.class,boolean.class);
        require(ChunkSnapshot.class,"getBlockData",int.class,int.class,int.class);
        require(ChunkSnapshot.class,"getBiome",int.class,int.class,int.class);
        require(BlockData.class,"getAsString");require(Bukkit.class,"getRegistry",Class.class);
    }
    static void require(Class<?> owner,String name,Class<?>... arguments) {
        try{owner.getMethod(name,arguments);}catch(ReflectiveOperationException error){throw new IllegalStateException("V2缺少必要平台能力："+owner.getName()+"."+name+"；需要兼容Paper/Folia，未退回同步读取或生成世界",error);}
    }
}
