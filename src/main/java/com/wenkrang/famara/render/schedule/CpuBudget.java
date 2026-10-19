package com.wenkrang.famara.render.schedule;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 所有 worker 共用的保守 CPU 欠账；批次有预约，超支不在周期边界清零。
 */
public final class CpuBudget {
    public record Snapshot(double debtNanos, long reservedNanos, long measuredCpuNanos, long settledBatches,
                           long maintenanceCpuNanos) {
    }

    private double rate;
    private long capacity;
    private final LongSupplier clock;
    private long last, reserved, total, batches, maintenance;
    private double debt;

    public CpuBudget(double cpuMillisPerSecond, double burstMillis, LongSupplier clock) {
        if (!Double.isFinite(cpuMillisPerSecond) || cpuMillisPerSecond <= 0 || cpuMillisPerSecond / 1000.0 == 0
                || !Double.isFinite(burstMillis) || burstMillis <= 0 || burstMillis > Long.MAX_VALUE / 1e6)
            throw new IllegalArgumentException("CPU预算必须为有界正数");
        this.rate = cpuMillisPerSecond / 1000.0;
        this.capacity = (long) (burstMillis * 1e6);
        if (capacity <= 0) throw new IllegalArgumentException("突发预算小于1ns");
        this.clock = Objects.requireNonNull(clock);
        this.last = clock.getAsLong();
    }

    public synchronized void reconfigure(double cpuMillisPerSecond,double burstMillis) {
        if(!Double.isFinite(cpuMillisPerSecond) || cpuMillisPerSecond<=0 || cpuMillisPerSecond/1000.0==0
                || !Double.isFinite(burstMillis) || burstMillis<=0 || burstMillis>Long.MAX_VALUE/1e6 || (long)(burstMillis*1e6)<=0)
            throw new IllegalArgumentException("CPU预算必须为有界正数");
        drain();rate=cpuMillisPerSecond/1000.0;capacity=(long)(burstMillis*1e6);
    }
    public final class Reservation implements AutoCloseable {
        private final long estimated;
        private final AtomicBoolean settled = new AtomicBoolean();

        private Reservation(long estimated) {
            this.estimated = estimated;
        }

        public void settle(long actualCpuNanos) {
            if (actualCpuNanos < 0) throw new IllegalArgumentException("CPU耗时不能为负");
            if (!settled.compareAndSet(false, true)) throw new IllegalStateException("CPU预约已结算");
            CpuBudget.this.finish(estimated, actualCpuNanos);
        }

        @Override
        public void close() {
            if (settled.compareAndSet(false, true)) CpuBudget.this.finish(estimated, 0);
        }
    }

    public synchronized Optional<Reservation> reserve(long estimatedCpuNanos) {
        validateEstimate(estimatedCpuNanos);
        drain();
        if (debt + reserved + estimatedCpuNanos > capacity) return Optional.empty();
        reserved = Math.addExact(reserved, estimatedCpuNanos);
        return Optional.of(new Reservation(estimatedCpuNanos));
    }

    public synchronized long retryAfterNanos(long estimatedCpuNanos) {
        validateEstimate(estimatedCpuNanos);
        drain();
        double deficit = debt + reserved + estimatedCpuNanos - capacity;
        if (deficit <= 0) return 0;
        if (reserved + estimatedCpuNanos > capacity) return Long.MAX_VALUE;
        return (long) Math.min(Long.MAX_VALUE, Math.ceil(deficit / rate));
    }

    public synchronized Snapshot snapshot() {
        drain();
        return new Snapshot(debt, reserved, total, batches, maintenance);
    }

    public synchronized void chargeMaintenance(long actualNanos) {
        if (actualNanos < 0) throw new IllegalArgumentException("CPU耗时不能为负");
        drain();
        debt += actualNanos;
        total = Math.addExact(total, actualNanos);
        maintenance = Math.addExact(maintenance, actualNanos);
    }

    private synchronized void finish(long estimate, long actual) {
        drain();
        reserved -= estimate;
        debt += actual;
        total = Math.addExact(total, actual);
        batches = Math.addExact(batches, 1);
    }

    private void validateEstimate(long value) {
        if (value <= 0 || value > capacity) throw new IllegalArgumentException("批次CPU预约超出突发容量");
    }

    private void drain() {
        long now = clock.getAsLong(), elapsed = now - last;
        if (elapsed < 0) throw new IllegalStateException("预算时钟后退");
        debt = Math.max(0, debt - elapsed * rate);
        last = now;
    }
}
