package dev.exceptionloader.reports.common.scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Abstraction over the Bukkit and BungeeCord schedulers.
 *
 * <p>The report system never creates threads or executor services of its own. Every piece of
 * blocking work goes through this interface and therefore through the platform's thread pool.
 */
public interface PlatformScheduler {

    /**
     * @return executor that hands tasks to the platform's async scheduler
     */
    @NotNull Executor async();

    @NotNull Task runAsyncLater(@NotNull Runnable task, @NotNull Duration delay);

    @NotNull Task runAsyncTimer(@NotNull Runnable task, @NotNull Duration delay, @NotNull Duration period);

    default void runAsync(@NotNull Runnable task) {
        async().execute(task);
    }

    default <T> @NotNull CompletableFuture<T> supplyAsync(@NotNull Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, async());
    }

    /**
     * Handle of a scheduled task.
     */
    @FunctionalInterface
    interface Task {
        void cancel();
    }
}
