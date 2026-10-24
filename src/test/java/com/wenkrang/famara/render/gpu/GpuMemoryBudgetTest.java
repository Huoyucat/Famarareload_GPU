package com.wenkrang.famara.render.gpu;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuMemoryBudgetTest {
    @Test void globalAndSingleAllocationLimitsFailBeforeAllocationAndReturnEveryLease() {
        var budget=new GpuMemoryBudget(1024,512);
        assertEquals(BackendException.Code.OUT_OF_MEMORY,assertThrows(BackendException.class,()->budget.reserve("oversized",513)).code());
        var first=budget.reserve("textures",512);var second=budget.reserve("scene",512);
        assertThrows(BackendException.class,()->budget.reserve("output",1));assertEquals(1024,budget.snapshot().live());
        first.close();first.close();second.close();assertEquals(0,budget.snapshot().live());assertTrue(budget.snapshot().domains().isEmpty());assertEquals(1024,budget.snapshot().peak());
    }
    @Test void concurrentContentionNeverExceedsHardBudget()throws Exception {
        var budget=new GpuMemoryBudget(128,64);var pool=Executors.newFixedThreadPool(8);List<Future<?>> work=new ArrayList<>();
        try {
            for(int i=0;i<200;i++)work.add(pool.submit(()->{
                try(var lease=budget.reserve("buffers",64)){assertTrue(budget.snapshot().live()<=128);Thread.yield();}
                catch(BackendException full){assertEquals(BackendException.Code.OUT_OF_MEMORY,full.code());}
            }));
            for(var task:work)task.get(5,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(0,budget.snapshot().live());assertTrue(budget.snapshot().peak()<=128);
    }
}
