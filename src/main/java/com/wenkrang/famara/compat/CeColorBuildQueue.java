package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 单拥有者、active+latest旧取色队列。与V2编译器独立工作，但共用一个CPU账本。 */
final class CeColorBuildQueue implements AutoCloseable {
    interface Work extends AutoCloseable {
        boolean current();
        boolean step(int operations,long deadline)throws Exception;
        void publish();
        void close();
    }
    record Metrics(long builds,long merged,long cancelled,long cpuNanos,boolean active,boolean pending) {}
    private record Request(long ticket,Supplier<Work> factory,CompletableFuture<Void> result) {}
    private final ScheduledThreadPoolExecutor executor;
    private final ThreadMXBean meter=ManagementFactory.getThreadMXBean();
    private volatile CpuBudget budget;
    private volatile BudgetedAssetLoader.Limits limits;
    private volatile Request requested;
    private volatile Request active;
    private long activeSince;
    private Work work;
    private ScheduledFuture<?> scheduled;
    private volatile boolean closed;
    private volatile long builds,merged,cancelled,cpuNanos;
    CeColorBuildQueue(CpuBudget budget,BudgetedAssetLoader.Limits limits) {
        configure(budget,limits);
        executor=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"Famara-ce-colors");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);
    }
    void configure(CpuBudget budget,BudgetedAssetLoader.Limits limits){this.budget=Objects.requireNonNull(budget);this.limits=Objects.requireNonNull(limits);budget.retryAfterNanos(limits.quantumCpuNanos());}
    synchronized CompletionStage<Void> offer(long ticket,Supplier<Work> factory) {
        if(closed)throw new RejectedExecutionException("旧取色队列已关闭");
        if(requested!=null && requested.ticket==ticket)return requested.result.minimalCompletionStage();
        if(requested!=null && requested!=active){merged++;requested.result.completeExceptionally(new CancellationException("旧取色请求已合并"));}
        requested=new Request(ticket,factory,new CompletableFuture<>());arm(0);return requested.result.minimalCompletionStage();
    }
    private synchronized void arm(long delay) {
        if(closed || scheduled!=null && !scheduled.isDone())return;
        scheduled=executor.schedule(this::step,delay,TimeUnit.NANOSECONDS);
    }
    private void dispose(Throwable error) {
        if(work!=null){try{work.close();}catch(Throwable close){if(error!=null)error.addSuppressed(close);else error=close;}work=null;}
        Request previous=active;active=null;
        if(previous!=null){if(error==null)previous.result.complete(null);else previous.result.completeExceptionally(error);}
    }
    private void step() {
        Request wanted;
        synchronized(this){scheduled=null;if(closed)return;wanted=requested;}
        if(active!=wanted){if(active!=null){cancelled++;dispose(new CancellationException("旧色构建已过期"));}active=wanted;activeSince=System.nanoTime();}
        if(active==null || active.result.isDone())return;
        CpuBudget ledger=budget;var policy=limits;var reservation=ledger.reserve(policy.quantumCpuNanos());
        if(reservation.isEmpty()){arm(Math.max(policy.dispatchNanos(),Math.min(1_000_000_000L,ledger.retryAfterNanos(policy.quantumCpuNanos()))));return;}
        long wall=System.nanoTime(),start=cpu();boolean finished=false;Throwable failure=null;
        try {
            if(work==null){work=active.factory.get();builds++;}
            if(System.nanoTime()-activeSince>=policy.timeoutNanos())throw new TimeoutException("旧色索引构建超时");
            if(closed || active!=requested || !work.current())throw new CancellationException("旧色索引输入已更新");
            finished=work.step(policy.operationsPerStep(),wall+policy.maxStepNanos());
            if(finished){if(closed || active!=requested || !work.current())throw new CancellationException("旧色索引提交已过期");work.publish();}
        }catch(Throwable error){failure=error;}
        finally{long end=cpu(),cost=start>=0 && end>=start?end-start:Math.max(0,System.nanoTime()-wall);cpuNanos+=cost;reservation.get().settle(cost);}
        if(failure!=null){if(failure instanceof CancellationException)cancelled++;dispose(failure);}
        else if(finished)dispose(null);
        synchronized(this){if(!closed && (active!=null || requested!=null && !requested.result.isDone()))arm(policy.dispatchNanos());}
        if(failure instanceof VirtualMachineError fatal)throw fatal;
    }
    private long cpu(){return meter.isCurrentThreadCpuTimeSupported() && meter.isThreadCpuTimeEnabled()?meter.getCurrentThreadCpuTime():-1;}
    Metrics metrics(){return new Metrics(builds,merged,cancelled,cpuNanos,active!=null && !active.result.isDone(),requested!=null && requested!=active && !requested.result.isDone());}
    @Override public synchronized void close() {
        if(closed)return;closed=true;if(scheduled!=null)scheduled.cancel(false);
        Request last=requested;if(last!=null && last!=active)last.result.completeExceptionally(new CancellationException("旧色队列关闭"));
        executor.execute(()->dispose(new CancellationException("旧色队列关闭")));executor.shutdown();
    }
}
