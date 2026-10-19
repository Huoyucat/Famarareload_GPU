package com.wenkrang.famara.render.schedule;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 小型后台IO/元数据工作队列，有界、串行，并把CPU开销记到照片共用预算。 */
public final class BudgetedWorkRunner implements AutoCloseable {
    private record Work<T>(Supplier<T> action,CompletableFuture<T> result) {}
    private final ArrayDeque<Work<?>> queue=new ArrayDeque<>();
    private final int maximum;
    private final long interval,quantum;
    private final CpuBudget budget;
    private final ScheduledThreadPoolExecutor executor;
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private final boolean cpuSupported;
    private boolean closed,running;
    private ScheduledFuture<?> scheduled;
    public BudgetedWorkRunner(int maximum,long interval,long quantum,CpuBudget budget) {
        if(maximum<=0 || interval<1_000_000 || quantum<=0)throw new IllegalArgumentException("后台工作限额无效");
        this.maximum=maximum;this.interval=interval;this.quantum=quantum;this.budget=Objects.requireNonNull(budget);budget.retryAfterNanos(quantum);
        boolean supported=meter.isCurrentThreadCpuTimeSupported();
        if(supported)try{if(!meter.isThreadCpuTimeEnabled())meter.setThreadCpuTimeEnabled(true);}catch(SecurityException | UnsupportedOperationException e){supported=false;}
        cpuSupported=supported;
        executor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"Famara-v2-io");t.setDaemon(true);return t;});executor.setRemoveOnCancelPolicy(true);
    }
    public synchronized <T> CompletionStage<T> submit(Supplier<T> action) {
        if(closed || queue.size()+(running?1:0)>=maximum)throw new RejectedExecutionException("后台工作队列已满或关闭");
        CompletableFuture<T> result=new CompletableFuture<>();queue.addLast(new Work<>(Objects.requireNonNull(action),result));arm(0);return result.minimalCompletionStage();
    }
    private synchronized void arm(long delay) {
        if(closed || running || scheduled!=null && !scheduled.isDone() || queue.isEmpty())return;
        scheduled=executor.schedule(this::next,delay,TimeUnit.NANOSECONDS);
    }
    private void next() {
        Work<?> work;CpuBudget.Reservation reservation;
        synchronized(this) {
            scheduled=null;if(closed || queue.isEmpty())return;
            Optional<CpuBudget.Reservation> allowed=budget.reserve(quantum);
            if(allowed.isEmpty()){arm(Math.max(interval,Math.min(1_000_000_000L,budget.retryAfterNanos(quantum))));return;}
            reservation=allowed.get();work=queue.removeFirst();running=true;
        }
        long wall=System.nanoTime(),start=cpuSupported?meter.getCurrentThreadCpuTime():-1;
        Object value=null;Throwable error=null;
        try{value=work.action.get();}catch(Throwable e){error=e;}
        finally {
            long end=cpuSupported?meter.getCurrentThreadCpuTime():-1;
            reservation.settle(start>=0 && end>=start?end-start:Math.max(0,System.nanoTime()-wall));
        }
        // action和CPU结算已经退出；完成回调可以用maximum=1的队列提交下一片。
        synchronized(this){running=false;}
        complete(work,value,error);
        synchronized(this){arm(interval);}
        if(error instanceof VirtualMachineError fatal)throw fatal;
    }
    @SuppressWarnings("unchecked") private static <T> void complete(Work<T> work,Object value,Throwable error) {
        if(error!=null)work.result.completeExceptionally(error);else work.result.complete((T)value);
    }
    public synchronized int outstanding(){return queue.size()+(running?1:0);}
    public synchronized void closeAfter(Runnable cleanup) {
        if(closed)return;closed=true;if(scheduled!=null)scheduled.cancel(false);
        List<Work<?>> pending=new ArrayList<>(queue);queue.clear();
        executor.execute(()->{try{pending.forEach(w->w.result.completeExceptionally(new CancellationException("后台服务关闭")));}finally{cleanup.run();}});executor.shutdown();
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;if(scheduled!=null)scheduled.cancel(false);
        List<Work<?>> pending=new ArrayList<>(queue);queue.clear();
        executor.execute(()->pending.forEach(w->w.result.completeExceptionally(new CancellationException("后台工作服务关闭"))));executor.shutdown();
    }
}
