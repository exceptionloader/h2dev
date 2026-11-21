package dev.exceptionloader.reports.bungee;

import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;

@Singleton
public class BungeePlatformScheduler implements PlatformScheduler {

    private final Plugin plugin;
    private final TaskScheduler scheduler;
    private final Executor async;

    @Inject
    public BungeePlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getProxy().getScheduler();
        this.async = task -> scheduler.runAsync(plugin, task);
    }

    @Override
    public Executor async() {
        return async;
    }

    @Override
    public Task runAsyncLater(Runnable task, Duration delay) {
        ScheduledTask scheduled = scheduler.schedule(plugin, task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return scheduled::cancel;
    }

    @Override
    public Task runAsyncTimer(Runnable task, Duration delay, Duration period) {
        ScheduledTask scheduled = scheduler.schedule(plugin, task, delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        return scheduled::cancel;
    }
}
