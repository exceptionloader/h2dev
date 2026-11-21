package dev.exceptionloader.reports.common.messaging;

import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * Fire-and-forget messaging between all server instances of the network.
 *
 * <p>Every instance receives every message, including the ones it published itself. Handlers run on
 * the subscriber thread and must not block it; platform code switches to the main thread where
 * needed.
 */
public interface MessageBus extends AutoCloseable {

    /**
     * Registers a handler. Handlers registered after {@link #start()} are picked up immediately.
     */
    <T> void subscribe(@NotNull String channel, @NotNull Class<T> type, @NotNull Consumer<? super T> handler);

    /**
     * Serializes and publishes a message asynchronously. Failures are logged, not thrown.
     */
    void publish(@NotNull String channel, @NotNull Object message);

    /**
     * Opens the subscription. Lost connections are re-established automatically.
     */
    void start();

    @Override
    void close();
}
