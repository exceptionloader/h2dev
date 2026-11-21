package dev.exceptionloader.reports.common.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.exceptionloader.reports.common.config.ReportSettings;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.messaging.RedisConnector;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.jetbrains.annotations.Blocking;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Network wide report cooldown backed by Redis.
 *
 * <p>Checking and setting the cooldown is a single atomic script, so two reports sent at the same
 * time (double click, two servers) cannot both pass. If Redis is down the cooldown falls back to
 * this instance only, which is still enough to stop spam from a single player.
 */
@Singleton
public class ReportCooldowns {

    private static final String KEY_PREFIX = "reports:cooldown:";
    private static final String ACQUIRE_SCRIPT = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) then
                return 0
            end
            return redis.call('PTTL', KEYS[1])
            """;

    private final RedisConnector redis;
    private final Clock clock;
    private final Logger logger;
    private final Cache<UUID, Long> localExpiry;

    @Inject
    public ReportCooldowns(RedisConnector redis, ReportSettings settings, Clock clock, @PluginLogger Logger logger) {
        this.redis = redis;
        this.clock = clock;
        this.logger = logger;
        this.localExpiry = CacheBuilder.newBuilder()
                .expireAfterWrite(settings.cooldown().isZero() ? Duration.ofSeconds(1) : settings.cooldown())
                .maximumSize(10_000)
                .build();
    }

    /**
     * Starts the cooldown for a player unless one is already running.
     *
     * @return the remaining time of the running cooldown, empty if a new one was started
     */
    @Blocking
    public Optional<Duration> tryAcquire(UUID player, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return Optional.empty();
        }
        try {
            Object reply = redis.execute(jedis -> jedis.eval(
                    ACQUIRE_SCRIPT, List.of(KEY_PREFIX + player), List.of(Long.toString(cooldown.toMillis()))));
            long remaining = reply instanceof Long millis ? millis : 0;
            return remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
        } catch (JedisException exception) {
            logger.warning("Redis unavailable, using local report cooldown: " + exception.getMessage());
            return acquireLocally(player, cooldown);
        }
    }

    @Blocking
    public void release(UUID player) {
        localExpiry.invalidate(player);
        try {
            redis.execute(jedis -> jedis.del(KEY_PREFIX + player));
        } catch (JedisException ignored) {
            // the key expires on its own
        }
    }

    private Optional<Duration> acquireLocally(UUID player, Duration cooldown) {
        long now = clock.millis();
        long[] remaining = {0};
        localExpiry.asMap().compute(player, (id, until) -> {
            if (until != null && until > now) {
                remaining[0] = until - now;
                return until;
            }
            return now + cooldown.toMillis();
        });
        return remaining[0] > 0 ? Optional.of(Duration.ofMillis(remaining[0])) : Optional.empty();
    }
}
