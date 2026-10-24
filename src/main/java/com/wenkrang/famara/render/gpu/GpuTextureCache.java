package com.wenkrang.famara.render.gpu;

import java.util.*;
import java.util.function.Supplier;

/** Worker-confined LRU sub-budget. Each value owns its global GPU allocation lease. */
public final class GpuTextureCache<V extends AutoCloseable> implements AutoCloseable {
    public record Key(long generation,String fingerprint,String atlasKey) {
        public Key{if(generation<0)throw new IllegalArgumentException("Negative generation");Objects.requireNonNull(fingerprint);Objects.requireNonNull(atlasKey);if(fingerprint.length()>256 || atlasKey.length()>256)throw new IllegalArgumentException("Unbounded native cache key");}
    }
    public record Snapshot(long maximum,long live,long peak,int entries,long hits,long misses) {}
    private final class Entry {
        final Key key;final V value;final long bytes;int references;boolean retired;
        Entry(Key key,V value,long bytes){this.key=key;this.value=value;this.bytes=bytes;}
    }
    private final Thread owner=Thread.currentThread();
    private final long maximum;
    private final int maximumEntries;
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private long live,peak,hits,misses;
    private boolean closed;
    public GpuTextureCache(long maximum){this(maximum,128);}
    public GpuTextureCache(long maximum,int maximumEntries){if(maximum<=0 || maximumEntries<1 || maximumEntries>128)throw new IllegalArgumentException("Positive bounded cache limits required");this.maximum=maximum;this.maximumEntries=maximumEntries;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Texture cache accessed outside its GPU worker");}
    public Lease acquire(Key key,long bytes,Supplier<V> load) {
        owner();if(closed)throw new BackendException(BackendException.Code.CLOSED,"Texture cache closed");
        if(bytes<=0 || bytes>maximum)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Texture cache entry exceeds its sub-budget");
        Entry entry=entries.get(key);
        if(entry!=null && !entry.retired){if(entry.bytes!=bytes)throw new IllegalStateException("Cache key reused with a different layout");hits++;entry.references++;return new Lease(entry);}
        if(entry!=null)throw new BackendException(BackendException.Code.UNAVAILABLE,"Retired texture generation still pinned");
        evict(bytes);misses++;V value=Objects.requireNonNull(load.get());
        try{entry=new Entry(key,value,bytes);entry.references=1;entries.put(key,entry);}
        catch(Throwable failure){try{value.close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
        live+=bytes;peak=Math.max(peak,live);return new Lease(entry);
    }
    private void evict(long incoming) {
        for(var iterator=entries.entrySet().iterator();(incoming>maximum-live || entries.size()>=maximumEntries) && iterator.hasNext();) {
            Entry entry=iterator.next().getValue();if(entry.references!=0)continue;iterator.remove();dispose(entry);
        }
        if(incoming>maximum-live || entries.size()>=maximumEntries)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"Pinned textures fill cache budget");
    }
    public void invalidateExcept(long generation,String fingerprint) {
        owner();for(var iterator=entries.entrySet().iterator();iterator.hasNext();) {
            Entry entry=iterator.next().getValue();if(entry.key.generation==generation && entry.key.fingerprint.equals(fingerprint))continue;
            entry.retired=true;if(entry.references==0){iterator.remove();dispose(entry);}
        }
    }
    private void dispose(Entry entry) {
        live-=entry.bytes;
        try{entry.value.close();}catch(Exception failure){throw new BackendException(BackendException.Code.DEVICE_ERROR,"GPU texture disposal failed",failure);}
    }
    public Snapshot snapshot(){owner();return new Snapshot(maximum,live,peak,entries.size(),hits,misses);}
    public final class Lease implements AutoCloseable {
        private Entry entry;
        private Lease(Entry entry){this.entry=entry;}
        public V value(){owner();if(entry==null)throw new IllegalStateException("Texture lease closed");return entry.value;}
        @Override public void close(){owner();if(entry==null)return;Entry released=entry;entry=null;if(--released.references==0 && released.retired){entries.remove(released.key,released);dispose(released);}}
    }
    @Override public void close() {
        owner();if(closed)return;closed=true;RuntimeException failed=null;
        for(var iterator=entries.entrySet().iterator();iterator.hasNext();) {
            Entry entry=iterator.next().getValue();entry.retired=true;
            if(entry.references==0){iterator.remove();try{dispose(entry);}catch(RuntimeException failure){if(failed==null)failed=failure;else failed.addSuppressed(failure);}}
        }
        if(failed!=null)throw failed;
    }
}
