package com.wenkrang.famara.render.schedule;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;

/** V2所有消费者共用的存活预约；子域不得另外获得一份全局额度。非JVM/OS物理堆硬配额。 */
public final class RenderMemoryBudget {
    public record Snapshot(long maximum,long live,long peak,Map<String,Long> domains) {}
    private final long maximum;
    private long live,peak;
    private final Map<String,Long> domains=new TreeMap<>();
    public RenderMemoryBudget(long maximum){if(maximum<=0)throw new IllegalArgumentException("总内存预算必须为正");this.maximum=maximum;}
    public final class Lease implements AutoCloseable {
        private String domain;
        private long bytes;
        private boolean closed;
        private Lease(String domain,long bytes){this.domain=domain;this.bytes=bytes;}
        public long bytes(){synchronized(RenderMemoryBudget.this){return bytes;}}
        public void resize(long next) {
            synchronized(RenderMemoryBudget.this) {
                if(closed)throw new IllegalStateException("内存租约已关闭");
                if(next<0 || next-bytes>maximum-live)throw rejected(domain,next-bytes);
                add(domain,next-bytes);bytes=next;
            }
        }
        public void transfer(String next){synchronized(RenderMemoryBudget.this){if(closed)throw new IllegalStateException("内存租约已关闭");add(domain,-bytes);domain=Objects.requireNonNull(next);add(domain,bytes);}}
        @Override public void close(){synchronized(RenderMemoryBudget.this){if(closed)return;closed=true;add(domain,-bytes);bytes=0;}}
    }
    public synchronized Lease reserve(String domain,long bytes) {
        Objects.requireNonNull(domain);if(bytes<0 || bytes>maximum-live)throw rejected(domain,bytes);
        add(domain,bytes);return new Lease(domain,bytes);
    }
    private RejectedExecutionException rejected(String domain,long bytes){return new RejectedExecutionException("V2总内存预算不足：域="+domain+"，追加="+bytes+"，存活="+live+"，总上限="+maximum);}
    private void add(String domain,long delta){live+=delta;peak=Math.max(peak,live);long next=domains.getOrDefault(domain,0L)+delta;if(next==0)domains.remove(domain);else domains.put(domain,next);}
    public synchronized Snapshot snapshot(){return new Snapshot(maximum,live,peak,Map.copyOf(domains));}
}
