package com.wenkrang.famara.render.schedule;

import java.util.*;

/**
 * 有界 DRR 玩家轮转 + 固定间隔漏桶。一次 poll 最多放行一个批次，不积累闲置信用。
 */
public final class LeakyWorkQueue<T> {
    public record Work<T>(UUID player, UUID job, T value, long estimatedCpuNanos) {
    }

    private static final class Player<T> {
        final ArrayDeque<Work<T>> queue = new ArrayDeque<>();
        long deficit;
        int inFlight;
    }

    private final int maximum;
    private final long interval, quantum, maxBatch;
    private final LinkedHashMap<UUID, Player<T>> players = new LinkedHashMap<>();
    private final Set<UUID> queued = new HashSet<>();
    private final Map<UUID, Work<T>> inFlight = new HashMap<>();
    private final LinkedHashMap<UUID, Long> recentDeficits = new LinkedHashMap<>();
    private int size;
    private long next;
    private boolean started;
    private final boolean virtualRounds;

    public LeakyWorkQueue(int maximum, long intervalNanos, long quantumCpuNanos, long maxBatchCpuNanos) {
        this(maximum,intervalNanos,quantumCpuNanos,maxBatchCpuNanos,false);
    }
    public LeakyWorkQueue(int maximum,long intervalNanos,long quantumCpuNanos,long maxBatchCpuNanos,boolean virtualRounds) {
        this.virtualRounds=virtualRounds;
        if (maximum <= 0 || intervalNanos <= 0 || quantumCpuNanos <= 0 || maxBatchCpuNanos < quantumCpuNanos
                || maxBatchCpuNanos > Long.MAX_VALUE / 4) throw new IllegalArgumentException("漏桶参数不合法");
        this.maximum = maximum;
        this.interval = intervalNanos;
        this.quantum = quantumCpuNanos;
        this.maxBatch = maxBatchCpuNanos;
    }

    public synchronized boolean offer(Work<T> work) {
        Objects.requireNonNull(work);
        Objects.requireNonNull(work.player);
        Objects.requireNonNull(work.job);
        Objects.requireNonNull(work.value);
        if (work.estimatedCpuNanos <= 0 || work.estimatedCpuNanos > maxBatch)
            throw new IllegalArgumentException("批次成本超界");
        if (size + inFlight.size() >= maximum || inFlight.containsKey(work.job) || !queued.add(work.job)) return false;
        Player<T> player = players.get(work.player);
        if (player == null) {
            player = new Player<>();
            Long balance = recentDeficits.remove(work.player);
            if (balance != null) player.deficit = balance;
            players.put(work.player, player);
        }
        player.queue.addLast(work);
        size++;
        return true;
    }

    public synchronized Optional<Work<T>> poll(long nowNanos) {
        if (size == 0 || started && nowNanos - next < 0) return Optional.empty();
        if(virtualRounds) {
            // DRR仅负责玩家公平，不再把虚拟轮次绑成第二个每tick CPU限额；吞吐由共享CpuBudget保证。
            long roundsToReady=Long.MAX_VALUE;
            for(Player<T> player:players.values()) {
                Work<T> head=player.queue.peekFirst();if(head==null)continue;
                long missing=Math.max(0,head.estimatedCpuNanos-player.deficit);
                roundsToReady=Math.min(roundsToReady,Math.max(1,missing/quantum+(missing%quantum==0?0:1)));
            }
            long skip=roundsToReady==Long.MAX_VALUE?0:roundsToReady-1;
            if(skip>0)for(Player<T> player:players.values())if(!player.queue.isEmpty()) {
                double replenished=player.deficit+(double)skip*quantum;player.deficit=(long)Math.min(maxBatch,replenished);
            }
        }
        int rounds = players.size();
        while (rounds-- > 0) {
            UUID key = players.keySet().iterator().next();
            Player<T> player = players.remove(key);
            Work<T> work = player.queue.peekFirst();
            if (work == null) {
                if (player.inFlight > 0) players.put(key, player);
                continue;
            }
            player.deficit = Math.min(maxBatch, player.deficit + quantum);
            if (work.estimatedCpuNanos > player.deficit) {
                players.put(key, player);
                continue;
            }
            player.queue.removeFirst();
            player.deficit -= work.estimatedCpuNanos;
            player.inFlight++;
            players.put(key, player);
            inFlight.put(work.job, work);
            queued.remove(work.job);
            size--;
            started = true;
            next = nowNanos + interval;
            return Optional.of(work);
        }
        started = true;
        next = nowNanos + interval;
        return Optional.empty();
    }

    public synchronized void account(Work<T> work, long actual) {
        if (actual < 0) throw new IllegalArgumentException("成本不合法");
        if (!inFlight.remove(work.job, work)) throw new IllegalStateException("批次未放行或已结算");
        Player<T> state = players.get(work.player);
        double corrected = (double) state.deficit + work.estimatedCpuNanos - actual;
        state.deficit = (long) Math.max(-Long.MAX_VALUE / 4.0, Math.min(maxBatch, corrected));
        state.inFlight--;
        if (state.inFlight == 0 && state.queue.isEmpty()) {
            players.remove(work.player);
            remember(work.player, state.deficit);
        }
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean remove(UUID job) {
        if (!queued.remove(job)) return false;
        Iterator<Map.Entry<UUID, Player<T>>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Player<T> player = entry.getValue();
            if (player.queue.removeIf(w -> w.job.equals(job))) {
                size--;
                if (player.queue.isEmpty() && player.inFlight == 0) {
                    it.remove();
                    remember(entry.getKey(), player.deficit);
                }
                return true;
            }
        }
        throw new IllegalStateException("队列身份索引不一致");
    }

    private void remember(UUID player, long deficit) {
        recentDeficits.put(player, deficit);
        while (recentDeficits.size() > 2L * maximum) recentDeficits.remove(recentDeficits.keySet().iterator().next());
    }

}
