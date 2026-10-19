package com.wenkrang.famara.compat;

import java.io.*;
import java.util.*;
import java.util.function.*;

/** 4MiB LRU只按存活缓存收费；根列表为过滤视图，不再复制一份全部路径。 */
final class ColorSourceCache implements PackColorIndex.PackSource,AutoCloseable {
    private final PackColorIndex.PackSource source;
    private final Function<String,byte[]> fallback;
    private final Predicate<String> roots;
    private final BooleanSupplier current;
    private final ColorWorkBudget memory;
    private final LinkedHashMap<String,byte[]> cache=new LinkedHashMap<>(16,.75f,true);
    private long cached,traffic,requests;
    private boolean closed;
    ColorSourceCache(PackColorIndex.PackSource source,Function<String,byte[]> fallback,Set<String> roots,BooleanSupplier current,ColorWorkBudget memory){this(source,fallback,roots==null?null:roots::contains,current,memory,true);}
    ColorSourceCache(PackColorIndex.PackSource source,Function<String,byte[]> fallback,Predicate<String> roots,BooleanSupplier current,ColorWorkBudget memory,boolean filtered){this.source=source;this.fallback=fallback;this.roots=roots;this.current=current;this.memory=memory;}
    private void check(){if(closed || !current.getAsBoolean())throw new java.util.concurrent.CancellationException("CE颜色来源已关闭或过期");}
    private boolean selected(String path){return path.matches("assets/[^/]+/blockstates/.+[.]json") || (roots==null?path.matches("assets/[^/]+/(items/.+[.]json|models/item/.+[.]json)"):roots.test(path));}
    public Collection<String> list()throws IOException {
        check();Collection<String> names=source.list();
        return new AbstractCollection<>() {
            public Iterator<String> iterator(){return new Iterator<>() {
                final Iterator<String> input=names.iterator();String next;
                public boolean hasNext(){check();while(next==null && input.hasNext()){String candidate=input.next();if(selected(candidate))next=candidate;}return next!=null;}
                public String next(){if(!hasNext())throw new NoSuchElementException();String value=next;next=null;return value;}
            };}
            public int size(){int size=0;for(String ignored:this)size++;return size;}
        };
    }
    public byte[] read(String path)throws IOException {
        check();requests++;byte[] value=cache.get(path);if(value!=null)return value;
        value=source.read(path);check();if(value==null)value=fallback.apply(path);if(value==null)return null;
        traffic=Math.addExact(traffic,value.length);if(value.length>4194304)throw new java.util.concurrent.RejectedExecutionException("旧取色单文件超过4MiB："+path);
        long bytes=value.length+192L+path.length()*4L;
        while(!cache.isEmpty() && (cached+bytes>4194304 || memory.available()<bytes)) {
            var oldest=cache.entrySet().iterator();var entry=oldest.next();long size=entry.getValue().length+192L+entry.getKey().length()*4L;oldest.remove();cached-=size;memory.release("cache",size);
        }
        if(bytes<=4194304 && memory.available()>=bytes){memory.retain("cache",bytes);cache.put(path,value);cached+=bytes;}
        return value;
    }
    long trafficBytes(){return traffic;}
    long requests(){return requests;}
    long cachedBytes(){return cached;}
    public void close(){if(closed)return;closed=true;cache.clear();memory.release("cache",cached);cached=0;}
}
