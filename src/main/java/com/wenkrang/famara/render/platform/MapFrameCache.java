package com.wenkrang.famara.render.platform;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 全服务共享且有界的16KiB输出源缓存；不复制Bukkit自己持有的canvas缓冲。 */
public final class MapFrameCache {
    record Frame(long version,byte[] indices) {}
    private com.wenkrang.famara.render.schedule.RenderMemoryBudget.Lease memory;
    public synchronized void memory(com.wenkrang.famara.render.schedule.RenderMemoryBudget global){memory=global.reserve("map-source-cache",(long)maximum*20000);}
    public synchronized void close(){frames.clear();if(memory!=null)memory.close();memory=null;}
    private final int maximum;
    private final AtomicLong versions=new AtomicLong();
    private final LinkedHashMap<Object,Frame> frames=new LinkedHashMap<>(16,.75f,true);
    public MapFrameCache(int maximum){if(maximum<=0)throw new IllegalArgumentException("输出缓存容量必须为正");this.maximum=maximum;}
    synchronized Frame put(Object owner,byte[] data) {
        if(data.length!=16384)throw new IllegalArgumentException("地图必须为128×128");
        for(byte value:data)if((value&255)<4)throw new IllegalArgumentException("照片不能包含透明调色板索引");
        Frame frame=new Frame(versions.incrementAndGet(),data.clone());frames.put(owner,frame);
        while(frames.size()>maximum)frames.remove(frames.keySet().iterator().next());return frame;
    }
    synchronized Frame get(Object owner){return frames.get(owner);}
    public synchronized int size(){return frames.size();}
    public synchronized void clear(){frames.clear();}
}
