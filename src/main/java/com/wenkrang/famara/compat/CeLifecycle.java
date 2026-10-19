package com.wenkrang.famara.compat;

import com.wenkrang.famara.Famara;
import com.wenkrang.famara.lib.Sched;
import org.bukkit.event.*;
import org.bukkit.event.server.*;

/** 不依赖CE类型的生命周期监听；不能在CE停用后继续把载体状态解释成自定义外观。 */
public final class CeLifecycle implements Listener {
    @EventHandler(priority=EventPriority.MONITOR)
    public void disabled(PluginDisableEvent event) {
        if(event.getPlugin().getName().equalsIgnoreCase("CraftEngine"))CeCompat.unavailable();
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void enabled(PluginEnableEvent event) {
        if(!event.getPlugin().getName().equalsIgnoreCase("CraftEngine"))return;
        CeCompat.invalidateVisualResources();
        Sched.global(()->{
            var owner=Famara.plugin;if(owner==null || !owner.isEnabled() || CeCompat.enabled())return;
            CeCompat.initialize(owner);CeCompat.ensureLoaded();CeCompat.refreshResources(com.wenkrang.famara.lib.DataFolders.of(owner).toPath().resolve("ce-color-index.txt"));
            long ticket=CeCompat.requestRebuild();CeCompat.scheduleRebuild(ticket);
        });
    }
}
