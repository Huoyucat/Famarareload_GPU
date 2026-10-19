package com.wenkrang.famara.render.schedule;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BudgetedWorkRunnerTest {
    @Test void boundedWorkIsSerializedAndSharesCpuReservations() throws Exception {
        CpuBudget budget=new CpuBudget(5000,100,System::nanoTime);CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var runner=new BudgetedWorkRunner(2,1_000_000,1_000_000,budget)) {
            var first=runner.submit(()->{started.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return 1;});
            assertTrue(started.await(5,TimeUnit.SECONDS));var second=runner.submit(()->2);
            assertThrows(RejectedExecutionException.class,()->runner.submit(()->3));assertEquals(2,runner.outstanding());
            assertEquals(1_000_000,budget.snapshot().reservedNanos());release.countDown();
            assertEquals(1,first.toCompletableFuture().get(5,TimeUnit.SECONDS));assertEquals(2,second.toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(0,budget.snapshot().reservedNanos());assertEquals(2,budget.snapshot().settledBatches());
        } finally{release.countDown();}
    }
    @Test void closeCompletesPendingWorkWithoutExecutingIt() throws Exception {
        CpuBudget budget=new CpuBudget(1,1,System::nanoTime);var held=budget.reserve(1_000_000).orElseThrow();
        var runner=new BudgetedWorkRunner(2,1_000_000,1_000_000,budget);
        try {
            var pending=runner.submit(()->{throw new AssertionError();});runner.close();
            assertTrue(pending.handle((r,e)->e!=null).toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(1_000_000,budget.snapshot().reservedNanos());assertThrows(RejectedExecutionException.class,()->runner.submit(()->1));
        } finally{runner.close();held.close();}
    }
}
