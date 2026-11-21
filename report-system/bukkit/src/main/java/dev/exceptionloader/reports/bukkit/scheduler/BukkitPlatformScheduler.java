package dev.exceptionloader.reports.bukkit.scheduler;

import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

@Singleton
public class BukkitPlatformScheduler implements PlatformScheduler {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;
    private final Executor async;

    @Inject
    public BukkitPlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getServer().getScheduler();
        this.async = task -> {
            if (plugin.isEnabled()) {
                scheduler.runTaskAsynchronously(plugin, task);
            } else {
                // the scheduler rejects tasks of disabled plugins; this only happens during shutdown
                task.run();
            }
        };
    }

    @Override
    public Executor async() {
        return async;
    }

    @Override
    public Task runAsyncLater(Runnable task, Duration delay) {
        BukkitTask scheduled = scheduler.runTaskLaterAsynchronously(plugin, task, ticks(delay));
        return scheduled::cancel;
    }

    @Override
    public Task runAsyncTimer(Runnable task, Duration delay, Duration period) {
        BukkitTask scheduled = scheduler.runTaskTimerAsynchronously(plugin, task, ticks(delay), ticks(period));
        return scheduled::cancel;
    }

    static long ticks(Duration duration) {
        return Math.max(1, duration.toMillis() / 50);
    }
}
