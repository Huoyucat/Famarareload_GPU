package com.wenkrang.famara.render.asset;

/** 构建期的保守分配预约，覆盖源数据、JSON、几何和纹理解码，避免只限制PNG文件字节。 */
public final class AssetMemoryBudget {
    private final long maximum;
    private long reserved;
    public AssetMemoryBudget(long maximum) {
        if(maximum<=0)throw new IllegalArgumentException("资产工作内存限额必须为正");
        this.maximum=maximum;
    }
    public synchronized void reserve(long bytes,String resource) {
        if(bytes<0 || bytes>maximum-reserved)
            throw new AssetException(AssetException.Code.LIMIT,resource,"资产工作内存预约超过限额");
        reserved+=bytes;
    }
    public synchronized long reservedBytes() { return reserved; }
    public long maximumBytes() { return maximum; }
}
