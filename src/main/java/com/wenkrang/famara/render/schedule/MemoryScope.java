package com.wenkrang.famara.render.schedule;

/** 已从总账本预付的工作集；内部每次增长必须先收费，阶段临时重叠也占这份额度。 */
public final class MemoryScope implements AutoCloseable {
    private final RenderMemoryBudget.Lease parent;
    private final long maximum;
    private long live,peak;
    private boolean closed;
    public MemoryScope(RenderMemoryBudget global,String domain,long bytes){parent=global.reserve(domain,bytes);maximum=bytes;}
    public synchronized void claim(long bytes) {
        if(closed || bytes<0 || bytes>maximum-live)throw new java.util.concurrent.RejectedExecutionException("任务工作集不足：追加="+bytes+"，存活="+live+"，预付上限="+maximum);
        live+=bytes;peak=Math.max(peak,live);
    }
    public synchronized void release(long bytes){if(bytes<0 || bytes>live)throw new IllegalStateException("工作集释放数量错误");live-=bytes;}
    /** 几何/冻结数据owner退出后只保留输出工作集；不可继续回到准备/渲染阶段。 */
    public synchronized void outputOnly(){if(closed)return;live=16L*1024*1024;parent.resize(live);}
    public synchronized long live(){return live;}
    public synchronized long peak(){return peak;}
    public long maximum(){return maximum;}
    @Override public synchronized void close(){if(closed)return;closed=true;parent.close();}
}
