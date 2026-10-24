package com.wenkrang.famara.render.gpu;

import java.util.*;

/** Plugin-owned OpenCL buffer bytes; the driver's context overhead is reported separately. */
public final class GpuMemoryBudget {
    public record Snapshot(long maximum,long maximumAllocation,long live,long peak,Map<String,Long> domains) {}
    private final long maximum,maximumAllocation;
    private final Map<String,Long> domains=new HashMap<>();
    private long live,peak;
    public GpuMemoryBudget(long maximum,long maximumAllocation) {
        if(maximum<=0 || maximumAllocation<=0)throw new IllegalArgumentException("GPU byte limits must be positive");
        this.maximum=maximum;this.maximumAllocation=maximumAllocation;
    }
    public synchronized Lease reserve(String domain,long bytes) {
        if(domain==null || domain.isBlank() || bytes<=0)throw new IllegalArgumentException("Invalid GPU reservation");
        if(bytes>maximumAllocation || bytes>maximum-live)throw new BackendException(BackendException.Code.OUT_OF_MEMORY,"GPU buffer exceeds plugin/device allocation budget");
        live+=bytes;peak=Math.max(peak,live);domains.merge(domain,bytes,Math::addExact);return new Lease(domain,bytes);
    }
    public synchronized Snapshot snapshot(){return new Snapshot(maximum,maximumAllocation,live,peak,Map.copyOf(domains));}
    public final class Lease implements AutoCloseable {
        private final String domain;
        private final long bytes;
        private boolean released;
        private Lease(String domain,long bytes){this.domain=domain;this.bytes=bytes;}
        @Override public void close(){synchronized(GpuMemoryBudget.this){if(released)return;released=true;live-=bytes;long next=domains.get(domain)-bytes;if(next==0)domains.remove(domain);else domains.put(domain,next);}}
    }
}
