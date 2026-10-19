package com.wenkrang.famara.render.platform;

import org.bukkit.entity.Player;
import org.bukkit.map.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 回调只复制已准备索引；缓存被逐出时请求有界后台恢复，不在游戏线程读盘。 */
public final class ImmutableMapRenderer extends MapRenderer {
    private final Map<MapCanvas,Long> drawn=new WeakHashMap<>();
    private final int maximumCanvases;
    private final MapFrameCache cache;
    private final Object cacheKey=new Object();
    private final Runnable reload;
    private final AtomicBoolean loading=new AtomicBoolean();
    private volatile long version;
    public ImmutableMapRenderer(int maximumCanvases){this(maximumCanvases,new MapFrameCache(1),()->{});}
    public ImmutableMapRenderer(int maximumCanvases,MapFrameCache cache,Runnable reload) {
        super(false);if(maximumCanvases<=0)throw new IllegalArgumentException("canvas缓存容量必须为正");
        this.maximumCanvases=maximumCanvases;this.cache=Objects.requireNonNull(cache);this.reload=Objects.requireNonNull(reload);
    }
    public boolean publish(byte[] indices,BooleanSupplier current) {
        Objects.requireNonNull(current);if(!current.getAsBoolean())return false;
        MapFrameCache.Frame next=cache.put(cacheKey,indices);version=next.version();loading.set(false);return true;
    }
    public void loadFinished(){loading.set(false);}
    @Override public void render(MapView map,MapCanvas canvas,Player player) {
        synchronized(drawn) {
            if(version!=0 && Objects.equals(drawn.get(canvas),version))return;
            MapFrameCache.Frame frame=cache.get(cacheKey);
            if(frame==null) {
                if(loading.compareAndSet(false,true))try{reload.run();}catch(RuntimeException error){loading.set(false);}
                return;
            }
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)canvas.setPixel(x,y,frame.indices()[y*128+x]);
            if(drawn.size()>=maximumCanvases && !drawn.containsKey(canvas))drawn.clear();
            drawn.put(canvas,frame.version());
        }
    }
}
