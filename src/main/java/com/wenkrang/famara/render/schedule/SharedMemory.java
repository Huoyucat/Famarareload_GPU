package com.wenkrang.famara.render.schedule;

/** 资源表/旧取色结果在服务当前视图与准备会话之间共享的内存所有权。 */
public final class SharedMemory implements AutoCloseable {
    private RenderMemoryBudget.Lease lease;
    private int references=1;
    private boolean ownerClosed;
    public SharedMemory(RenderMemoryBudget.Lease lease){this.lease=lease;}
    public synchronized AutoCloseable retain() {
        if(references==0)throw new IllegalStateException("共享内存已释放");references++;
        return new AutoCloseable(){boolean closed;public void close(){synchronized(SharedMemory.this){if(closed)return;closed=true;SharedMemory.this.release();}}};
    }
    private void release(){if(--references==0){if(lease!=null)lease.close();lease=null;}}
    @Override public synchronized void close(){if(ownerClosed)return;ownerClosed=true;release();}
}
