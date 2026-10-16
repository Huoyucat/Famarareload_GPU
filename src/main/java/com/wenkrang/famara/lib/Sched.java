package com.wenkrang.famara.lib;

import com.wenkrang.famara.Famara;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Paper/Folia 调度门面，不在业务签名中暴露 Folia 类型。 */
public final class Sched {
    interface Backend {
        void global(Task task, Runnable run, long delay, long period);
        void async(Task task, Runnable run, long delay, long period);
        void entity(Task task, Entity entity, Runnable run, long delay, long period);
        void region(Task task, World world, int x, int z, Runnable run);
        boolean owns(World world, int x, int z);
        void cancelAll();
    }
    private static final Set<Task> TASKS = ConcurrentHashMap.newKeySet();
    private static final Backend BACKEND = createBackend();
    private Sched() {}

    private static Backend createBackend() {
        boolean api;
        try {
            org.bukkit.Bukkit.class.getMethod("getRegionScheduler");
            org.bukkit.Bukkit.class.getMethod("getGlobalRegionScheduler");
            org.bukkit.Bukkit.class.getMethod("getAsyncScheduler");
            Class<?> plugin = org.bukkit.plugin.Plugin.class;
            Class<?> global = org.bukkit.Bukkit.class.getMethod("getGlobalRegionScheduler").getReturnType();
            Class<?> region = org.bukkit.Bukkit.class.getMethod("getRegionScheduler").getReturnType();
            Class<?> async = org.bukkit.Bukkit.class.getMethod("getAsyncScheduler").getReturnType();
            Class<?> entity = Entity.class.getMethod("getScheduler").getReturnType();
            global.getMethod("run", plugin, Consumer.class);
            global.getMethod("runDelayed", plugin, Consumer.class, long.class);
            global.getMethod("runAtFixedRate", plugin, Consumer.class, long.class, long.class);
            global.getMethod("cancelTasks", plugin);
            region.getMethod("run", plugin, World.class, int.class, int.class, Consumer.class);
            async.getMethod("runNow", plugin, Consumer.class);
            async.getMethod("runDelayed", plugin, Consumer.class, long.class, java.util.concurrent.TimeUnit.class);
            async.getMethod("runAtFixedRate", plugin, Consumer.class, long.class, long.class, java.util.concurrent.TimeUnit.class);
            async.getMethod("cancelTasks", plugin);
            entity.getMethod("runDelayed", plugin, Consumer.class, Runnable.class, long.class);
            entity.getMethod("runAtFixedRate", plugin, Consumer.class, Runnable.class, long.class, long.class);
            org.bukkit.Bukkit.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
            api = true;
        } catch (NoSuchMethodException e) { api = false; }
        if (api) {
            try {
                return (Backend) Class.forName("com.wenkrang.famara.lib.RegionSchedulers", true,
                        Sched.class.getClassLoader()).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("区域调度适配器初始化失败", e);
            }
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, Sched.class.getClassLoader());
            throw new IllegalStateException("检测到 Folia 但缺少区域调度能力，拒绝使用 Bukkit 主线程回退");
        } catch (ClassNotFoundException e) { return new BukkitSchedulers(); }
    }

    public static String mode() { return BACKEND.getClass().getSimpleName().equals("RegionSchedulers") ? "Paper/Folia 区域调度 API" : "Paper Bukkit 主线程调度"; }
    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Runnable cancellation = () -> {};
        private final boolean repeating;
        private Task(boolean repeating) { this.repeating = repeating; TASKS.add(this); }
        void attach(Runnable cancel) { cancellation = cancel; if (cancelled.get()) cancel.run(); }
        void invoke(Runnable run) {
            if (cancelled.get()) return;
            try { run.run(); }
            catch (RuntimeException | Error e) { cancel(); throw e; }
            finally { if (!repeating) TASKS.remove(this); }
        }
        public void cancel() { if (cancelled.compareAndSet(false, true)) cancellation.run(); TASKS.remove(this); }
    }
    public static void globalLater(Runnable run, long ticks) {
        Task task = new Task(false); BACKEND.global(task, run, Math.max(1, ticks), 0);
    }
    public static void global(Runnable run) {
        Task task = new Task(false); BACKEND.global(task, run, 0, 0);
    }
    public static Task globalTimer(Runnable run, long delay, long period) {
        Task task = new Task(true); BACKEND.global(task, run, Math.max(1, delay), Math.max(1, period)); return task;
    }
    public static void async(Runnable run) {
        Task task = new Task(false); BACKEND.async(task, run, 0, 0);
    }
    public static Task asyncTimer(Runnable run, long delay, long period) {
        Task task = new Task(true); BACKEND.async(task, run, Math.max(0, delay), Math.max(1, period)); return task;
    }
    public static void entity(Entity entity, Runnable run) {
        Task task = new Task(false); BACKEND.entity(task, entity, run, 1, 0);
    }
    public static Task entityTimer(Entity entity, Consumer<Task> run, long delay, long period) {
        Task task = new Task(true); BACKEND.entity(task, entity, () -> run.accept(task), Math.max(1, delay), Math.max(1, period)); return task;
    }
    public static void region(World world, int x, int z, Runnable run) {
        Task task = new Task(false); BACKEND.region(task, world, x, z, run);
    }
    public static boolean owns(World world, int x, int z) { return BACKEND.owns(world, x, z); }
    public static void cancelAll() {
        for (Task task : Set.copyOf(TASKS)) task.cancel();
        BACKEND.cancelAll();
    }
}
