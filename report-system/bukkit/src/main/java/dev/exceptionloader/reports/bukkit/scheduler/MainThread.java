package dev.exceptionloader.reports.bukkit.scheduler;

import dev.exceptionloader.reports.bukkit.config.Messages;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Brings results of async operations back to the server thread.
 */
@Singleton
public class MainThread {

    private final Plugin plugin;
    private final Messages messages;
    private final Logger logger;
    private final Executor executor;

    @Inject
    public MainThread(Plugin plugin, Messages messages, @PluginLogger Logger logger) {
        this.plugin = plugin;
        this.messages = messages;
        this.logger = logger;
        this.executor = task -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (plugin.getServer().isPrimaryThread()) {
                task.run();
            } else {
                plugin.getServer().getScheduler().runTask(plugin, task);
            }
        };
    }

    public Executor executor() {
        return executor;
    }

    public void run(Runnable task) {
        executor.execute(task);
    }

    public void later(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    public void later(Runnable task, long ticks) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, task, ticks);
        }
    }

    /**
     * Runs {@code action} on the main thread once the future completes. Failures are logged and
     * the sender gets a generic error message instead of silence.
     */
    public <T> void handle(CompletableFuture<T> future, CommandSender sender, Consumer<? super T> action) {
        future.whenCompleteAsync((result, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Report operation failed", unwrap(error));
                messages.send(sender, "general.error");
                return;
            }
            try {
                action.accept(result);
            } catch (RuntimeException exception) {
                logger.log(Level.SEVERE, "Could not process report result", exception);
                messages.send(sender, "general.error");
            }
        }, executor);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
