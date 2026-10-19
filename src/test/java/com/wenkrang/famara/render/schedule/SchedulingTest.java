package com.wenkrang.famara.render.schedule;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingTest {
    private static final long MS = 1_000_000L;

    @Test
    void reservationsShareOneGlobalCpuCapacity() throws Exception {
        AtomicLong clock = new AtomicLong();
        CpuBudget budget = new CpuBudget(200, 10, clock::get);
        List<CpuBudget.Reservation> accepted = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++)
                tasks.add(() -> {
                    budget.reserve(MS).ifPresent(accepted::add);
                    return null;
                });
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        assertEquals(10, accepted.size());
        assertEquals(10 * MS, budget.snapshot().reservedNanos());
        accepted.forEach(r -> r.settle(2 * MS));
        assertEquals(20 * MS, budget.snapshot().debtNanos(), 1e-6);
        assertTrue(budget.reserve(MS).isEmpty());
        clock.set(100 * MS);
        assertTrue(budget.reserve(MS).isPresent());
    }

    @Test
    void expensiveBatchDebtSurvivesTheNextTickAndIsRepaid() {
        AtomicLong clock = new AtomicLong();
        CpuBudget budget = new CpuBudget(100, 5, clock::get);
        var reservation = budget.reserve(MS).orElseThrow();
        reservation.settle(15 * MS);
        clock.set(50 * MS);
        assertTrue(budget.reserve(MS).isEmpty());
        assertEquals(10 * MS, budget.snapshot().debtNanos(), 1e-6);
        clock.set(150 * MS);
        var next = budget.reserve(MS).orElseThrow();
        next.close();
        next.close();
        assertEquals(0, budget.snapshot().reservedNanos());
        assertThrows(IllegalStateException.class, () -> reservation.settle(0));
    }

    @Test
    void idleTimeCannotAccumulateUnlimitedBurstCredit() {
        AtomicLong clock = new AtomicLong();
        CpuBudget budget = new CpuBudget(200, 5, clock::get);
        clock.set(3600L * 1000 * MS);
        var a = budget.reserve(5 * MS).orElseThrow();
        assertTrue(budget.reserve(1).isEmpty());
        a.close();
        assertEquals(0, budget.snapshot().reservedNanos());
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(6 * MS));
    }

    @Test
    void admissionIsBoundedPerPlayerGloballyAndByMemory() {
        AdmissionController c = new AdmissionController(new AdmissionController.Limits(3, 1, 100));
        UUID player = UUID.randomUUID(), request = UUID.randomUUID();
        var first = c.admit(player, request, 40);
        assertTrue(first.accepted());
        assertEquals(AdmissionController.Rejection.DUPLICATE, c.admit(player, request, 1).rejection());
        assertEquals(AdmissionController.Rejection.PLAYER_LIMIT, c.admit(player, UUID.randomUUID(), 1).rejection());
        assertEquals(AdmissionController.Rejection.MEMORY_LIMIT, c.admit(UUID.randomUUID(), UUID.randomUUID(), 61).rejection());
        assertTrue(first.permit().grow(60));
        assertFalse(first.permit().grow(1));
        first.permit().close();
        first.permit().close();
        assertEquals(0, c.snapshot().reservedBytes());
        assertFalse(first.permit().grow(1));
        c.stopAdmission();
        assertEquals(AdmissionController.Rejection.CLOSED, c.admit(player, UUID.randomUUID(), 1).rejection());
    }

    @Test
    void concurrentAdmissionNeverExceedsSharedCountOrMemory() throws Exception {
        AdmissionController c = new AdmissionController(new AdmissionController.Limits(10, 10, 100));
        List<AdmissionController.Permit> accepted = Collections.synchronizedList(new ArrayList<>());
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++)
                tasks.add(() -> {
                    var r = c.admit(UUID.randomUUID(), UUID.randomUUID(), 10);
                    if (r.accepted()) accepted.add(r.permit());
                    return null;
                });
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        assertEquals(10, accepted.size());
        assertEquals(100, c.snapshot().reservedBytes());
        accepted.forEach(AdmissionController.Permit::close);
        assertEquals(new AdmissionController.Snapshot(0, 0, 0), c.snapshot());
    }

    @Test
    void leakyQueueDoesNotCatchUpAfterAnIdlePeriod() {
        LeakyWorkQueue<String> queue = new LeakyWorkQueue<>(3, 100, 10, 100);
        UUID p = UUID.randomUUID();
        assertTrue(queue.offer(new LeakyWorkQueue.Work<>(p, UUID.randomUUID(), "a", 10)));
        assertTrue(queue.offer(new LeakyWorkQueue.Work<>(p, UUID.randomUUID(), "b", 10)));
        var first = queue.poll(1000000).orElseThrow();
        assertTrue(queue.poll(1000000).isEmpty());
        assertTrue(queue.poll(1000099).isEmpty());
        queue.account(first, 10);
        var second = queue.poll(1000100).orElseThrow();
        queue.account(second, 10);
        assertTrue(queue.poll(1000200).isEmpty());
        assertThrows(IllegalStateException.class, () -> queue.account(second, 10));
    }

    @Test
    void differentPlayersReceiveRoundRobinService() {
        LeakyWorkQueue<String> q = new LeakyWorkQueue<>(10, 10, 10, 100);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        q.offer(new LeakyWorkQueue.Work<>(a, UUID.randomUUID(), "a1", 10));
        q.offer(new LeakyWorkQueue.Work<>(a, UUID.randomUUID(), "a2", 10));
        q.offer(new LeakyWorkQueue.Work<>(b, UUID.randomUUID(), "b1", 10));
        var first = q.poll(0).orElseThrow();
        assertEquals(a, first.player());
        q.account(first, 10);
        var second = q.poll(10).orElseThrow();
        assertEquals(b, second.player());
        q.account(second, 10);
        var third = q.poll(20).orElseThrow();
        assertEquals(a, third.player());
        q.account(third, 10);
    }

    @Test
    void inFlightWorkAlsoConsumesQueueCapacityAndCannotBeEnqueuedTwice() {
        LeakyWorkQueue<String> q = new LeakyWorkQueue<>(1, 10, 10, 100);
        var work = new LeakyWorkQueue.Work<>(UUID.randomUUID(), UUID.randomUUID(), "a", 10);
        assertTrue(q.offer(work));
        assertEquals(work, q.poll(0).orElseThrow());
        assertFalse(q.offer(work));
        assertFalse(q.offer(new LeakyWorkQueue.Work<>(UUID.randomUUID(), UUID.randomUUID(), "b", 10)));
        q.account(work, 20);
        assertTrue(q.offer(work));
    }

    @Test
    void highCostWorkAccumulatesBoundedDeficitRatherThanStarving() {
        LeakyWorkQueue<String> q = new LeakyWorkQueue<>(2, 10, 10, 100);
        var costly = new LeakyWorkQueue.Work<>(UUID.randomUUID(), UUID.randomUUID(), "costly", 30);
        q.offer(costly);
        assertTrue(q.poll(0).isEmpty());
        assertTrue(q.poll(10).isEmpty());
        var work = q.poll(20).orElseThrow();
        assertEquals(costly, work);
        q.account(work, 40);
    }

    private static PhotoJobState encodingJob() {
        PhotoJobState state = new PhotoJobState();
        PhotoJobState.Stage[] path = {PhotoJobState.Stage.REQUESTED, PhotoJobState.Stage.ADMITTED, PhotoJobState.Stage.CAPTURING,
                PhotoJobState.Stage.PREPARING, PhotoJobState.Stage.READY, PhotoJobState.Stage.RENDERING, PhotoJobState.Stage.ENCODING};
        for (int i = 1; i < path.length; i++) assertTrue(state.advance(path[i - 1], path[i]));
        return state;
    }

    @Test
    void pixelCompletionDoesNotBypassStorageAndInstallationReceipts() {
        PhotoJobState state = encodingJob();
        var token = state.beginCommit().orElseThrow();
        assertThrows(IllegalStateException.class, () -> state.succeed(token));
        assertThrows(IllegalStateException.class, () -> state.installed(token));
        assertTrue(state.stored(token));
        assertThrows(IllegalStateException.class, () -> state.succeed(token));
        assertTrue(state.installed(token));
        assertTrue(state.succeed(token));
        assertFalse(state.succeed(token));
        assertEquals(PhotoJobState.Stage.SUCCEEDED, state.state().stage());
    }

    @Test
    void cancellationCannotRollbackTheCommittingState() {
        PhotoJobState state = encodingJob();
        var token = state.beginCommit().orElseThrow();
        assertEquals(PhotoJobState.CancelResult.TOO_LATE, state.cancel("cancel"));
        state.stored(token);
        assertTrue(state.fail("installation failed"));
        assertTrue(state.state().stored());
        assertFalse(state.installed(token));
        assertFalse(state.succeed(token));
        assertEquals(PhotoJobState.Stage.FAILED, state.state().stage());
    }

    @Test
    void cancelledOrExpiredJobCannotLaterSucceed() {
        PhotoJobState state = encodingJob();
        assertEquals(PhotoJobState.CancelResult.CANCELLED, state.cancel("cancel"));
        assertTrue(state.beginCommit().isEmpty());
        assertFalse(state.expire());
        assertEquals(PhotoJobState.CancelResult.ALREADY_TERMINAL, state.cancel("again"));
        PhotoJobState other = encodingJob();
        var token = other.beginCommit().orElseThrow();
        assertTrue(other.expire());
        assertFalse(other.stored(token));
        assertEquals(PhotoJobState.Stage.FAILED, other.state().stage());
    }

    @Test
    void cancellationAndCommitRaceHasOneLinearizedOutcome() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 100; i++) {
                PhotoJobState state = encodingJob();
                CountDownLatch start = new CountDownLatch(1);
                var a = pool.submit(() -> {
                    start.await();
                    return state.beginCommit();
                });
                var b = pool.submit(() -> {
                    start.await();
                    return state.cancel("race");
                });
                start.countDown();
                var token = a.get();
                var cancelled = b.get();
                if (token.isPresent()) {
                    assertEquals(PhotoJobState.CancelResult.TOO_LATE, cancelled);
                    state.stored(token.get());
                    state.installed(token.get());
                    assertTrue(state.succeed(token.get()));
                } else assertEquals(PhotoJobState.Stage.CANCELLED, state.state().stage());
            }
        }
    }

    @Test
    void rescheduledSingleJobKeepsActualCostDebtEvenWhenItsQueueWasEmpty() {
        LeakyWorkQueue<String> q = new LeakyWorkQueue<>(1, 10, 10, 10);
        var work = new LeakyWorkQueue.Work<>(UUID.randomUUID(), UUID.randomUUID(), "expensive", 10);
        q.offer(work);
        q.poll(0).orElseThrow();
        q.account(work, 100);
        assertTrue(q.offer(work));
        for (int t = 10; t < 100; t += 10) assertTrue(q.poll(t).isEmpty(), "实际欠账不能在重入队时丢失");
        assertEquals(work, q.poll(100).orElseThrow());
        q.account(work, 10);
    }


    @Test void profileSwitchDrainsAtOldRateAndPreservesOutstandingReservationsAndDebt() {
        AtomicLong clock=new AtomicLong();CpuBudget budget=new CpuBudget(200,10,clock::get);
        budget.chargeMaintenance(8*MS);var held=budget.reserve(2*MS).orElseThrow();clock.set(10*MS);
        budget.reconfigure(1000,50);assertEquals(6*MS,budget.snapshot().debtNanos(),1e-6);assertEquals(2*MS,budget.snapshot().reservedNanos());
        held.settle(3*MS);clock.set(15*MS);budget.reconfigure(200,10);
        assertEquals(4*MS,budget.snapshot().debtNanos(),1e-6);assertEquals(0,budget.snapshot().reservedNanos());
        assertEquals(11*MS,budget.snapshot().measuredCpuNanos());assertTrue(budget.reserve(7*MS).isEmpty());
    }
    @Test void loweringBurstBelowExistingReservationsNeverCreatesNewCredit() {
        AtomicLong clock=new AtomicLong();CpuBudget budget=new CpuBudget(1000,50,clock::get);var held=budget.reserve(40*MS).orElseThrow();
        budget.reconfigure(200,25);assertEquals(40*MS,budget.snapshot().reservedNanos());assertTrue(budget.reserve(MS).isEmpty());
        held.settle(60*MS);assertEquals(60*MS,budget.snapshot().debtNanos(),1e-6);assertTrue(budget.reserve(MS).isEmpty());
        assertThrows(IllegalArgumentException.class,()->budget.reconfigure(Double.NaN,25));assertEquals(60*MS,budget.snapshot().debtNanos(),1e-6);
    }

    @Test void virtualFairRoundsCanFeedTwoCoresButNeverMintCpuBudget() {
        for(int rate:new int[]{200,2000}) {
            AtomicLong clock=new AtomicLong();CpuBudget budget=new CpuBudget(rate,100,clock::get);LeakyWorkQueue<String> queue=new LeakyWorkQueue<>(2,MS,MS,8*MS,true);UUID player=UUID.randomUUID();
            var a=new LeakyWorkQueue.Work<>(player,UUID.randomUUID(),"a",MS);var b=new LeakyWorkQueue.Work<>(player,UUID.randomUUID(),"b",MS);queue.offer(a);queue.offer(b);
            record Active(LeakyWorkQueue.Work<String> work,CpuBudget.Reservation cpu,long end) {}
            List<Active> active=new ArrayList<>();long used=0;
            for(long t=0;t<2000*MS;t+=MS) {
                clock.set(t);var iterator=active.iterator();
                while(iterator.hasNext()){var item=iterator.next();if(t>=item.end()){item.cpu().settle(8*MS);queue.account(item.work(),8*MS);used+=8*MS;queue.offer(item.work());iterator.remove();}}
                if(active.size()<2){var allowed=budget.reserve(MS);if(allowed.isPresent()){var next=queue.poll(t);if(next.isPresent())active.add(new Active(next.get(),allowed.get(),t+8*MS));else allowed.get().close();}}
            }
            assertTrue(used<rate*2L*MS+150*MS,"共享CPU总额不能按lane倍增");
            if(rate==2000)assertTrue(used>3000*MS,"DRR不能重新把双lane限速成1核");else assertTrue(used<600*MS);
            for(var item:active){item.cpu().close();queue.account(item.work(),0);}assertEquals(0,budget.snapshot().reservedNanos());
        }
    }
}
