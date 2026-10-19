package com.wenkrang.famara.render.schedule;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯状态机；资源回收由持有在途工作引用的协调器负责，不在 CAS 中执行 IO。
 */
public final class PhotoJobState {
    public enum Stage {
        REQUESTED, ADMITTED, CAPTURING, PREPARING, READY, RENDERING, ENCODING, COMMITTING,
        SUCCEEDED, FAILED, CANCELLED, EXPIRED, REJECTED;

        public boolean terminal() {
            return ordinal() >= SUCCEEDED.ordinal();
        }
    }

    public enum CancelResult {CANCELLED, TOO_LATE, ALREADY_TERMINAL}

    public record State(Stage stage, boolean stored, boolean installed, String reason, long revision) {
    }

    public final class CommitToken {
        private CommitToken() {
        }
    }

    private final AtomicReference<State> current = new AtomicReference<>(new State(Stage.REQUESTED, false, false, "", 0));
    private final CommitToken token = new CommitToken();

    public State state() {
        return current.get();
    }

    public boolean advance(Stage expected, Stage next) {
        if (!allowed(expected, next))
            throw new IllegalArgumentException("不允许的状态迁移：" + expected + " -> " + next);
        while (true) {
            State previous = current.get();
            if (previous.stage != expected) return false;
            State changed = new State(next, previous.stored, previous.installed, previous.reason, previous.revision + 1);
            if (current.compareAndSet(previous, changed)) return true;
        }
    }

    public Optional<CommitToken> beginCommit() {
        return advance(Stage.ENCODING, Stage.COMMITTING) ? Optional.of(token) : Optional.empty();
    }

    public boolean stored(CommitToken supplied) {
        return updateReceipt(supplied, false);
    }

    public boolean installed(CommitToken supplied) {
        return updateReceipt(supplied, true);
    }

    private boolean updateReceipt(CommitToken supplied, boolean installed) {
        if (supplied != token) throw new IllegalArgumentException("提交令牌不属于此任务");
        while (true) {
            State previous = current.get();
            if (previous.stage != Stage.COMMITTING) return false;
            if (installed && !previous.stored) throw new IllegalStateException("未存储照片不能标记地图已安装");
            State next = new State(Stage.COMMITTING, true, previous.installed || installed, previous.reason, previous.revision + 1);
            if (current.compareAndSet(previous, next)) return true;
        }
    }

    public boolean succeed(CommitToken supplied) {
        if (supplied != token) throw new IllegalArgumentException("提交令牌不属于此任务");
        while (true) {
            State previous = current.get();
            if (previous.stage != Stage.COMMITTING) return false;
            if (!previous.stored || !previous.installed) throw new IllegalStateException("存储与安装都完成后才能成功");
            if (current.compareAndSet(previous, new State(Stage.SUCCEEDED, true, true, "", previous.revision + 1)))
                return true;
        }
    }

    public boolean fail(String reason) {
        Objects.requireNonNull(reason);
        while (true) {
            State previous = current.get();
            if (previous.stage.terminal()) return false;
            Stage terminal = previous.stage == Stage.REQUESTED ? Stage.REJECTED : Stage.FAILED;
            if (current.compareAndSet(previous, new State(terminal, previous.stored, previous.installed, reason, previous.revision + 1)))
                return true;
        }
    }

    public CancelResult cancel(String reason) {
        Objects.requireNonNull(reason);
        while (true) {
            State previous = current.get();
            if (previous.stage.terminal()) return CancelResult.ALREADY_TERMINAL;
            if (previous.stage == Stage.COMMITTING) return CancelResult.TOO_LATE;
            Stage terminal = previous.stage == Stage.REQUESTED ? Stage.REJECTED : Stage.CANCELLED;
            if (current.compareAndSet(previous, new State(terminal, false, false, reason, previous.revision + 1)))
                return CancelResult.CANCELLED;
        }
    }

    public boolean expire() {
        while (true) {
            State previous = current.get();
            if (previous.stage.terminal()) return false;
            Stage next = previous.stage == Stage.REQUESTED ? Stage.REJECTED : previous.stage == Stage.COMMITTING ? Stage.FAILED : Stage.EXPIRED;
            if (current.compareAndSet(previous, new State(next, previous.stored, previous.installed, "任务超时", previous.revision + 1)))
                return true;
        }
    }

    private static boolean allowed(Stage from, Stage to) {
        return switch (from) {
            case REQUESTED -> to == Stage.ADMITTED || to == Stage.REJECTED;
            case ADMITTED -> to == Stage.CAPTURING;
            case CAPTURING -> to == Stage.PREPARING;
            case PREPARING -> to == Stage.READY;
            case READY -> to == Stage.RENDERING;
            case RENDERING -> to == Stage.ENCODING;
            case ENCODING -> to == Stage.COMMITTING;
            default -> false;
        };
    }
}
