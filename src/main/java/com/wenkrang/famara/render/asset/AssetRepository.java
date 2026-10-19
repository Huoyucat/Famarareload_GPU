package com.wenkrang.famara.render.asset;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 构建由受控后台调用；这里只做短发布操作，不在 acquire 时编译或等待。
 */
public final class AssetRepository implements AutoCloseable {
    private final int maxRetained;
    private final long maxRetainedBytes;
    private final AtomicLong requested = new AtomicLong();
    private final AtomicReference<Entry> current = new AtomicReference<>();
    private final Set<Entry> retained = ConcurrentHashMap.newKeySet();
    private boolean closed;

    private final class Entry {
        final AssetGeneration generation;
        final AtomicInteger references = new AtomicInteger(1);

        Entry(AssetGeneration generation) {
            this.generation = generation;
        }

        boolean retain() {
            int count;
            do {
                count = references.get();
                if (count == 0) return false;
            }
            while (!references.compareAndSet(count, Math.addExact(count, 1)));
            return true;
        }

        void release() {
            if(references.decrementAndGet()==0){retained.remove(this);generation.releaseMemory();}
        }
    }

    public final class Lease implements AutoCloseable {
        private volatile Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Entry entry) {
            this.entry = entry;
        }

        public AssetGeneration generation() {
            Entry owned=entry;
            if(released.get() || owned==null)throw new IllegalStateException("资源租约已释放");
            return owned.generation;
        }

        @Override
        public void close() {
            if(released.compareAndSet(false,true)) { Entry owned=entry;entry=null;owned.release(); }
        }
    }

    public AssetRepository(int maxRetained) {
        this(maxRetained,Long.MAX_VALUE);
    }

    public AssetRepository(int maxRetained,long maxRetainedBytes) {
        if(maxRetained<=0 || maxRetainedBytes<=0)throw new IllegalArgumentException("资源缓存限额必须为正");
        this.maxRetained=maxRetained;this.maxRetainedBytes=maxRetainedBytes;
    }

    public synchronized long requestBuild() {
        if (closed) throw new IllegalStateException("资源仓库已关闭");
        return requested.incrementAndGet();
    }

    public synchronized boolean publish(long ticket, AssetGeneration generation) {
        if (closed || requested.get() != ticket) return false;
        Objects.requireNonNull(generation);
        if (generation.id() != ticket) throw new IllegalArgumentException("构建ticket与代际ID不一致");
        Entry previous = current.get();
        boolean retired = false;
        if(generation.estimatedRetainedBytes()>maxRetainedBytes)return false;
        if(retained.size()>=maxRetained || generation.estimatedRetainedBytes()>maxRetainedBytes-retainedEstimatedBytes()) {
            if (previous == null || !previous.references.compareAndSet(1, 0)) return false;
            if(generation.estimatedRetainedBytes()>maxRetainedBytes-(retainedEstimatedBytes()-previous.generation.estimatedRetainedBytes())) {
                previous.references.set(1);return false;
            }
            retained.remove(previous);previous.generation.releaseMemory();
            retired = true;
        }
        Entry next = new Entry(generation);
        retained.add(next);
        current.set(next);
        if (previous != null && !retired) previous.release();
        return true;
    }

    public Lease acquire() {
        Entry entry = current.get();
        if (entry == null) throw new IllegalStateException("资源尚未准备完成");
        if (!entry.retain()) throw new IllegalStateException("资源代际正在切换，请稍后重试");
        return new Lease(entry);
    }

    public long retainedEstimatedBytes() {
        return retained.stream().mapToLong(e->e.generation.estimatedRetainedBytes()).sum();
    }

    public int retainedGenerations() {
        return retained.size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        requested.incrementAndGet();
        Entry previous = current.getAndSet(null);
        if (previous != null) previous.release();
    }
}
