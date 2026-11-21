package dev.exceptionloader.reports.common.messaging;

import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisException;

/**
 * {@link MessageBus} on top of Redis Pub/Sub.
 *
 * <p>The subscription occupies one pooled connection inside a long running async task of the
 * platform scheduler. When the connection drops, the task ends and a new attempt is scheduled with
 * exponential backoff. A watchdog pings the subscription regularly and tears it down if Redis stops
 * answering, which also covers half-open TCP connections that would otherwise block forever.
 */
@Singleton
public class RedisMessageBus implements MessageBus {

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(20);
    private static final Duration STALE_AFTER = PING_INTERVAL.multipliedBy(3);
    private static final int PUBLISH_ATTEMPTS = 3;

    private final RedisConnector redis;
    private final PlatformScheduler scheduler;
    private final MessageCodec codec;
    private final Logger logger;
    private final Map<String, List<Handler<?>>> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Subscription subscription;
    private volatile PlatformScheduler.Task watchdog;
    private volatile Duration backoff = INITIAL_BACKOFF;
    private volatile boolean interrupted;

    @Inject
    public RedisMessageBus(RedisConnector redis, PlatformScheduler scheduler, MessageCodec codec, @PluginLogger Logger logger) {
        this.redis = redis;
        this.scheduler = scheduler;
        this.codec = codec;
        this.logger = logger;
    }

    @Override
    public <T> void subscribe(String channel, Class<T> type, Consumer<? super T> handler) {
        handlers.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(new Handler<>(type, handler));
        Subscription current = subscription;
        if (current != null && current.isSubscribed()) {
            current.subscribe(channel);
        }
    }

    @Override
    public void publish(String channel, Object message) {
        String payload = codec.encode(message);
        scheduler.runAsync(() -> publish(channel, payload, 1));
    }

    private void publish(String channel, String payload, int attempt) {
        try {
            redis.execute(jedis -> jedis.publish(channel, payload));
        } catch (JedisException exception) {
            if (attempt >= PUBLISH_ATTEMPTS || !running.get()) {
                logger.warning("Dropping message for " + channel + " after " + attempt + " attempts: " + exception.getMessage());
                return;
            }
            scheduler.runAsyncLater(() -> publish(channel, payload, attempt + 1), Duration.ofSeconds(attempt));
        }
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.runAsync(this::listen);
            watchdog = scheduler.runAsyncTimer(this::checkSubscription, PING_INTERVAL, PING_INTERVAL);
        }
    }

    private void listen() {
        if (!running.get()) {
            return;
        }
        String[] channels = handlers.keySet().toArray(String[]::new);
        if (channels.length == 0) {
            logger.warning("Redis message bus started without any subscriptions");
            return;
        }

        Subscription current = new Subscription();
        try (Jedis jedis = redis.borrow()) {
            current.connection = jedis;
            subscription = current;
            jedis.subscribe(current, channels);
        } catch (JedisException exception) {
            if (running.get()) {
                if (!interrupted) {
                    logger.warning("Redis subscription failed (" + exception.getMessage() + "), retrying in the background");
                }
                interrupted = true;
            }
        } catch (RuntimeException exception) {
            logger.log(Level.SEVERE, "Unexpected error in Redis subscription", exception);
        } finally {
            current.connection = null;
            if (subscription == current) {
                subscription = null;
            }
        }

        if (running.get()) {
            Duration delay = backoff;
            backoff = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
            scheduler.runAsyncLater(this::listen, delay);
        }
    }

    private void checkSubscription() {
        Subscription current = subscription;
        if (current == null || !current.isSubscribed()) {
            return;
        }
        if (current.silentFor().compareTo(STALE_AFTER) > 0) {
            logger.warning("Redis subscription stopped responding, reconnecting");
            current.disconnect();
            return;
        }
        try {
            current.ping();
        } catch (JedisException exception) {
            current.disconnect();
        }
    }

    private void dispatch(String channel, String payload) {
        List<Handler<?>> registered = handlers.get(channel);
        if (registered == null) {
            return;
        }
        for (Handler<?> handler : registered) {
            try {
                handler.handle(codec, payload);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Failed to handle message on " + channel, exception);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        PlatformScheduler.Task task = watchdog;
        if (task != null) {
            task.cancel();
        }
        Subscription current = subscription;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (RuntimeException exception) {
                current.disconnect();
            }
        }
    }

    private record Handler<T>(Class<T> type, Consumer<? super T> consumer) {

        void handle(MessageCodec codec, String payload) {
            consumer.accept(codec.decode(payload, type));
        }
    }

    private final class Subscription extends JedisPubSub {

        private volatile Jedis connection;
        private volatile long lastActivity = System.nanoTime();

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            touch();
            backoff = INITIAL_BACKOFF;
            if (interrupted) {
                interrupted = false;
                logger.info("Redis subscription restored");
            }
        }

        @Override
        public void onMessage(String channel, String message) {
            touch();
            dispatch(channel, message);
        }

        @Override
        public void onPong(String pattern) {
            touch();
        }

        Duration silentFor() {
            return Duration.ofNanos(System.nanoTime() - lastActivity);
        }

        void disconnect() {
            Jedis jedis = connection;
            if (jedis != null) {
                try {
                    jedis.disconnect();
                } catch (RuntimeException ignored) {
                    // the subscriber loop notices the dead connection either way
                }
            }
        }

        private void touch() {
            lastActivity = System.nanoTime();
        }
    }
}
