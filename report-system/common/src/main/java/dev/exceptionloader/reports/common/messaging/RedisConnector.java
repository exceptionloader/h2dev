package dev.exceptionloader.reports.common.messaging;

import dev.exceptionloader.reports.common.config.RedisSettings;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.function.Function;
import org.jetbrains.annotations.Blocking;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Connection pool for Redis. Broken connections are detected on borrow and replaced, so a Redis
 * restart only affects the commands that were in flight.
 */
@Singleton
public class RedisConnector implements AutoCloseable {

    private final JedisPool pool;

    @Inject
    public RedisConnector(RedisSettings settings) {
        DefaultJedisClientConfig.Builder client = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis((int) settings.timeout().toMillis())
                .socketTimeoutMillis((int) settings.timeout().toMillis())
                .database(settings.database())
                .ssl(settings.ssl())
                .clientName("report-system");
        if (!settings.username().isEmpty()) {
            client.user(settings.username());
        }
        if (!settings.password().isEmpty()) {
            client.password(settings.password());
        }
        JedisClientConfig clientConfig = client.build();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setMaxWait(settings.timeout());
        // no idle evictor: it would run on a timer thread owned by commons-pool
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofMillis(-1));
        poolConfig.setTestWhileIdle(false);
        poolConfig.setJmxEnabled(false);

        this.pool = new JedisPool(poolConfig, new HostAndPort(settings.host(), settings.port()), clientConfig);
    }

    @Blocking
    public <T> T execute(Function<Jedis, T> command) {
        try (Jedis jedis = pool.getResource()) {
            return command.apply(jedis);
        }
    }

    /**
     * Borrows a connection for long running use such as a subscription. The caller closes it.
     */
    @Blocking
    Jedis borrow() {
        return pool.getResource();
    }

    @Override
    public void close() {
        pool.close();
    }
}
