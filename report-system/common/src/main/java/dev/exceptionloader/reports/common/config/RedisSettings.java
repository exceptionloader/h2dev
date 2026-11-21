package dev.exceptionloader.reports.common.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings for Redis.
 *
 * @param host     host name
 * @param port     port
 * @param username ACL user, empty for the default user
 * @param password password, empty if Redis runs without authentication
 * @param database logical database index
 * @param ssl      whether to use TLS
 * @param timeout  connect and read timeout for regular commands
 */
public record RedisSettings(
        String host,
        int port,
        String username,
        String password,
        int database,
        boolean ssl,
        Duration timeout) {

    public RedisSettings {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(timeout, "timeout");
        username = Objects.requireNonNullElse(username, "");
        password = Objects.requireNonNullElse(password, "");
    }
}
