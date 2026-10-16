package com.wenkrang.famara.lib;

import com.wenkrang.famara.Famara;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

/** 仅普通 Paper 缺少区域调度 API 时使用；不得在 Folia 上选择。 */
final class BukkitSchedulers implements Sched.Backend {
    public void global(Sched.Task task, Runnable run, long delay, long period) {
        BukkitTask raw = period > 0
                ? Bukkit.getScheduler().runTaskTimer(Famara.getPlugin(), () -> task.invoke(run), delay, period)
                : Bukkit.getScheduler().runTaskLater(Famara.getPlugin(), () -> task.invoke(run), delay);
        task.attach(raw::cancel);
    }
    public void async(Sched.Task task, Runnable run, long delay, long period) {
        BukkitTask raw = period > 0
                ? Bukkit.getScheduler().runTaskTimerAsynchronously(Famara.getPlugin(), () -> task.invoke(run), delay, period)
                : Bukkit.getScheduler().runTaskLaterAsynchronously(Famara.getPlugin(), () -> task.invoke(run), delay);
        task.attach(raw::cancel);
    }
    public void entity(Sched.Task task, Entity entity, Runnable run, long delay, long period) {
        global(task, () -> { if (entity.isValid()) run.run(); else task.cancel(); }, delay, period);
    }
    public void region(Sched.Task task, World world, int x, int z, Runnable run) { global(task, run, 0, 0); }
    public boolean owns(World world, int x, int z) { return Bukkit.isPrimaryThread(); }
    public void cancelAll() { Bukkit.getScheduler().cancelTasks(Famara.getPlugin()); }
}
