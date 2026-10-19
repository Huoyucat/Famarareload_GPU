package com.wenkrang.famara.render.schedule;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 拍照正式受理前预留数量与内存，不触碰胶卷或实时世界。
 */
public final class AdmissionController {
    public record Limits(int global, int perPlayer, long memoryBytes) {
        public Limits {
            if (global <= 0 || perPlayer <= 0 || perPlayer > global || memoryBytes <= 0)
                throw new IllegalArgumentException("准入限额不合法");
        }
    }

    public enum Rejection {NONE, CLOSED, DUPLICATE, GLOBAL_LIMIT, PLAYER_LIMIT, MEMORY_LIMIT}

    public record Outcome(Permit permit, Rejection rejection) {
        public boolean accepted() {
            return permit != null;
        }
    }

    public record Snapshot(int admitted, long reservedBytes, int players) {
    }

    private final Limits limits;
    private final Map<UUID, Permit> requests = new HashMap<>();
    private final Map<UUID, Integer> players = new HashMap<>();
    private long memory;
    private boolean closed;

    public final class Permit implements AutoCloseable {
        private final UUID player, request;
        private long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(UUID player, UUID request, long bytes) {
            this.player = player;
            this.request = request;
            this.bytes = bytes;
        }

        public UUID requestId() {
            return request;
        }

        public boolean grow(long extraBytes) {
            if (extraBytes <= 0) throw new IllegalArgumentException("追加预约必须为正");
            synchronized (AdmissionController.this) {
                if (released.get() || extraBytes > limits.memoryBytes - memory) return false;
                memory += extraBytes;
                bytes += extraBytes;
                return true;
            }
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) release(this);
        }
    }

    public AdmissionController(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    public synchronized Outcome admit(UUID player, UUID request, long estimatedBytes) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(request);
        if (estimatedBytes <= 0) throw new IllegalArgumentException("内存预约不能为负");
        if (closed) return new Outcome(null, Rejection.CLOSED);
        if (requests.containsKey(request)) return new Outcome(null, Rejection.DUPLICATE);
        if (requests.size() >= limits.global) return new Outcome(null, Rejection.GLOBAL_LIMIT);
        if (players.getOrDefault(player, 0) >= limits.perPlayer) return new Outcome(null, Rejection.PLAYER_LIMIT);
        if (estimatedBytes > limits.memoryBytes - memory) return new Outcome(null, Rejection.MEMORY_LIMIT);
        Permit permit = new Permit(player, request, estimatedBytes);
        requests.put(request, permit);
        players.merge(player, 1, Integer::sum);
        memory += estimatedBytes;
        return new Outcome(permit, Rejection.NONE);
    }

    private synchronized void release(Permit permit) {
        if (requests.remove(permit.request, permit)) {
            memory -= permit.bytes;
            int left = players.get(permit.player) - 1;
            if (left == 0) players.remove(permit.player);
            else players.put(permit.player, left);
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(requests.size(), memory, players.size());
    }

    public synchronized void stopAdmission() {
        closed = true;
    }
}
