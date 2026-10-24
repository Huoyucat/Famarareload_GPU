package com.wenkrang.famara.render.schedule;

/** 已从总账本预付的工作集；内部每次增长必须先收费，阶段临时重叠也占这份额度。 */
public final class MemoryScope implements AutoCloseable {
    private final RenderMemoryBudget.Lease parent;
    private final long maximum;
    private final boolean incremental;
    private long live,peak,reserved;
    private boolean closed;
    public MemoryScope(RenderMemoryBudget global,String domain,long bytes){this(global,domain,bytes,false);}
    private MemoryScope(RenderMemoryBudget global,String domain,long bytes,boolean incremental){
        if(bytes<0)throw new IllegalArgumentException("工作集上限不能为负");
        parent=global.reserve(domain,incremental?0:bytes);maximum=bytes;this.incremental=incremental;reserved=incremental?0:bytes;
    }
    /** Charge the shared budget before every allocation, retaining the same per-photo cap. */
    public static MemoryScope incremental(RenderMemoryBudget global,String domain,long maximum){return new MemoryScope(global,domain,maximum,true);}
    private long quantum(){return Math.max(1,Math.min(1024*1024,maximum/64));}
    private long rounded(long bytes){long q=quantum();return bytes+Math.min(maximum-bytes,(q-bytes%q)%q);}
    private void resize(long bytes){parent.resize(bytes);reserved=bytes;}
    public synchronized void claim(long bytes) {
        if(closed || bytes<0 || bytes>maximum-live)throw new java.util.concurrent.RejectedExecutionException("任务工作集不足：追加="+bytes+"，存活="+live+"，预付上限="+maximum);
        if(incremental && live+bytes>reserved)resize(rounded(live+bytes));
        live+=bytes;peak=Math.max(peak,live);
    }
    public synchronized void release(long bytes){if(bytes<0 || bytes>live)throw new IllegalStateException("工作集释放数量错误");if(incremental && reserved-rounded(live-bytes)>=2*quantum())resize(rounded(live-bytes));live-=bytes;}
    /** 几何/冻结数据owner退出后只保留输出工作集；不可继续回到准备/渲染阶段。 */
    public synchronized void outputOnly(){if(closed)return;long output=16L*1024*1024;resize(output);live=output;}
    public synchronized long live(){return live;}
    public synchronized long peak(){return peak;}
    public long maximum(){return maximum;}
    @Override public synchronized void close(){if(closed)return;closed=true;parent.close();reserved=0;}
}
