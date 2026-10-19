package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.schedule.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CeColorBuildQueueTest {
    private BudgetedAssetLoader.Limits limits(){return new BudgetedAssetLoader.Limits(1,1000000,1000000,5000000,10000000000L);}
    @Test void reloadStormHasOnlyOneOwnerAndTheLatestPendingBuild()throws Exception {
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);AtomicInteger built=new AtomicInteger(),closed=new AtomicInteger(),published=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var queue=new CeColorBuildQueue(budget,limits())) {
            var first=queue.offer(1,()->{built.incrementAndGet();return new CeColorBuildQueue.Work(){
                public boolean current(){return true;}
                public boolean step(int operations,long deadline)throws Exception{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return true;}
                public void publish(){throw new AssertionError("过期任务不能发布");}public void close(){closed.incrementAndGet();}
            };});
            assertTrue(entered.await(5,TimeUnit.SECONDS));CompletionStage<Void> latest=null;
            for(int i=2;i<=40;i++)latest=queue.offer(i,()->{built.incrementAndGet();return new CeColorBuildQueue.Work(){
                public boolean current(){return true;}public boolean step(int operations,long deadline){return true;}
                public void publish(){published.incrementAndGet();}public void close(){closed.incrementAndGet();}
            };});
            release.countDown();latest.toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertThrows(CompletionException.class,()->first.toCompletableFuture().join());assertEquals(2,built.get());assertEquals(2,closed.get());assertEquals(1,published.get());
            assertTrue(queue.metrics().merged()>=38);assertEquals(0,budget.snapshot().reservedNanos());
        }finally{release.countDown();}
    }
    @Test void closeWhileWaitingDoesNotSpendAnotherConsumersReservation()throws Exception {
        CpuBudget budget=new CpuBudget(1,1,System::nanoTime);var held=budget.reserve(1000000).orElseThrow();
        var queue=new CeColorBuildQueue(budget,limits());
        try {
            var pending=queue.offer(1,()->{throw new AssertionError("预算未放行不能开始");});queue.close();
            assertTrue(pending.handle((v,error)->error!=null).toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(1000000,budget.snapshot().reservedNanos());
        }finally{queue.close();held.close();}
    }
    @Test void cancelledReadPropagatesInsteadOfBeingTreatedAsMissingColor()throws Exception {
        var cursor=PackColorIndex.cursor(new PackColorIndex.PackSource(){
            public java.util.Collection<String> list(){return java.util.List.of("assets/test/items/leaf.json");}
            public byte[] read(String path){throw new CancellationException("reload");}
        },name->null);
        assertThrows(CancellationException.class,()->cursor.step(1,Long.MAX_VALUE,System::nanoTime,()->true));
        assertThrows(IllegalStateException.class,cursor::result);
    }

    @Test void memoryRejectionCannotBeSwallowedAsMissingJsonAndProducePartialIndex()throws Exception {
        var cursor=PackColorIndex.cursor(new PackColorIndex.PackSource(){
            public java.util.Collection<String> list(){return java.util.List.of("assets/test/items/leaf.json");}
            public byte[] read(String path){throw new RejectedExecutionException("shared budget exhausted");}
        },name->0x123456);
        assertThrows(RejectedExecutionException.class,()->cursor.step(1,Long.MAX_VALUE,System::nanoTime,()->true));assertThrows(IllegalStateException.class,cursor::result);
    }
}
