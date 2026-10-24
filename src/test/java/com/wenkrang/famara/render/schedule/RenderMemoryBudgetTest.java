package com.wenkrang.famara.render.schedule;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderMemoryBudgetTest {
    @Test void allDomainsAndReservationsShareOneMaximumAndTransfersDoNotAddCredit() {
        var memory=new RenderMemoryBudget(512);var assets=memory.reserve("assets",128);var snapshots=memory.reserve("snapshots",384);
        assertEquals(512,memory.snapshot().live());assertThrows(RejectedExecutionException.class,()->memory.reserve("scene",1));
        snapshots.resize(128);snapshots.transfer("compact-world");assertEquals(256,memory.snapshot().live());
        assertEquals(128,memory.snapshot().domains().get("compact-world"));assets.close();assets.close();snapshots.close();assertEquals(0,memory.snapshot().live());assertEquals(512,memory.snapshot().peak());
    }
    @Test void concurrentGrowthAndFailureNeverOverbookGlobalCapacity()throws Exception {
        var memory=new RenderMemoryBudget(512);List<RenderMemoryBudget.Lease> leases=Collections.synchronizedList(new ArrayList<>());
        try(var workers=Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> jobs=new ArrayList<>();for(int i=0;i<100;i++)jobs.add(()->{try{leases.add(memory.reserve("work",32));}catch(RejectedExecutionException full){}return null;});
            for(var result:workers.invokeAll(jobs))result.get();assertEquals(16,leases.size());assertEquals(512,memory.snapshot().live());leases.forEach(RenderMemoryBudget.Lease::close);
        }
        assertEquals(0,memory.snapshot().live());
    }
    @Test void prepaidProgressScopeBoundsOverlappingConversionWithoutDeadlock() {
        var memory=new RenderMemoryBudget(512);try(var scope=new MemoryScope(memory,"photo",128)) {
            scope.claim(80);scope.claim(48);assertThrows(RejectedExecutionException.class,()->scope.claim(1));scope.release(80);scope.claim(64);
            assertEquals(112,scope.live());assertEquals(128,scope.peak());assertEquals(128,memory.snapshot().live());
        }
        assertEquals(0,memory.snapshot().live());
    }
    @Test void incrementalPhotosShareUnusedCapacityAndFailedClaimsAreAtomic() {
        var memory=new RenderMemoryBudget(100);
        try(var first=MemoryScope.incremental(memory,"photo",80);var second=MemoryScope.incremental(memory,"photo",80)) {
            assertEquals(0,memory.snapshot().live());first.claim(35);second.claim(40);
            assertEquals(75,memory.snapshot().live());assertThrows(RejectedExecutionException.class,()->first.claim(30));
            assertEquals(35,first.live());assertEquals(75,memory.snapshot().live());
            second.release(20);first.claim(30);assertEquals(85,memory.snapshot().live());
            assertThrows(RejectedExecutionException.class,()->first.claim(16));assertEquals(65,first.live());
        }
        assertEquals(0,memory.snapshot().live());
    }
    @Test void incrementalReservationKeepsBoundedSlackAcrossTemporaryObjects() {
        var memory=new RenderMemoryBudget(64L*1024*1024);
        try(var scope=MemoryScope.incremental(memory,"photo",32L*1024*1024)) {
            for(int i=0;i<10000;i++){scope.claim(100);scope.release(100);}
            assertEquals(0,scope.live());assertEquals(512*1024,memory.snapshot().live());assertEquals(512*1024,memory.snapshot().peak());
            scope.claim(3L*1024*1024);scope.release(3L*1024*1024);assertEquals(0,memory.snapshot().live());
        }
        assertEquals(0,memory.snapshot().live());
    }
    @Test void sharedOwnerRetainsAllocationUntilTheLastConsumerExits()throws Exception {
        var memory=new RenderMemoryBudget(512);var shared=new SharedMemory(memory.reserve("table",32));var first=shared.retain();var second=shared.retain();
        shared.close();shared.close();assertEquals(32,memory.snapshot().live());first.close();assertEquals(32,memory.snapshot().live());second.close();second.close();assertEquals(0,memory.snapshot().live());
    }
}
