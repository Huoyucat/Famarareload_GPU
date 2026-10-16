package com.wenkrang.famara.lib;

import com.wenkrang.famara.Famara;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/** 仅在区域调度 API 存在时加载；Folia 世界访问不退到全局线程。 */
final class RegionSchedulers implements Sched.Backend {
    public void global(Sched.Task task, Runnable run, long delay, long period) {
        ScheduledTask raw;
        if (period > 0) raw = Bukkit.getGlobalRegionScheduler().runAtFixedRate(Famara.getPlugin(), t -> task.invoke(run), Math.max(1, delay), period);
        else if (delay > 0) raw = Bukkit.getGlobalRegionScheduler().runDelayed(Famara.getPlugin(), t -> task.invoke(run), delay);
        else raw = Bukkit.getGlobalRegionScheduler().run(Famara.getPlugin(), t -> task.invoke(run));
        task.attach(raw::cancel);
    }
    public void async(Sched.Task task, Runnable run, long delay, long period) {
        ScheduledTask raw;
        if (period > 0) raw = Bukkit.getAsyncScheduler().runAtFixedRate(Famara.getPlugin(), t -> task.invoke(run), delay * 50L, period * 50L, TimeUnit.MILLISECONDS);
        else if (delay > 0) raw = Bukkit.getAsyncScheduler().runDelayed(Famara.getPlugin(), t -> task.invoke(run), delay * 50L, TimeUnit.MILLISECONDS);
        else raw = Bukkit.getAsyncScheduler().runNow(Famara.getPlugin(), t -> task.invoke(run));
        task.attach(raw::cancel);
    }
    public void entity(Sched.Task task, Entity entity, Runnable run, long delay, long period) {
        ScheduledTask raw = period > 0
                ? entity.getScheduler().runAtFixedRate(Famara.getPlugin(), t -> task.invoke(run), task::cancel, Math.max(1, delay), period)
                : entity.getScheduler().runDelayed(Famara.getPlugin(), t -> task.invoke(run), task::cancel, Math.max(1, delay));
        if (raw == null) task.cancel(); else task.attach(raw::cancel);
    }
    public void region(Sched.Task task, World world, int x, int z, Runnable run) {
        ScheduledTask raw = Bukkit.getRegionScheduler().run(Famara.getPlugin(), world, x, z, t -> task.invoke(run));
        task.attach(raw::cancel);
    }
    public boolean owns(World world, int x, int z) { return Bukkit.isOwnedByCurrentRegion(world, x, z); }
    public void cancelAll() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(Famara.getPlugin());
        Bukkit.getAsyncScheduler().cancelTasks(Famara.getPlugin());
    }
}
