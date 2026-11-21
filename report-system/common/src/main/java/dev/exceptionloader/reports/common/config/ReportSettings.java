package dev.exceptionloader.reports.common.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Rules applied when players file reports.
 *
 * @param serverName         name of this server instance, stored with every report
 * @param cooldown           minimum time between two reports of the same player, network wide
 * @param maxOpenPerReporter open reports a single player may have at once, {@code 0} disables the limit
 * @param minDetailsLength   minimum length of free text reasons
 * @param maxDetailsLength   maximum length of free text reasons
 */
public record ReportSettings(
        String serverName,
        Duration cooldown,
        int maxOpenPerReporter,
        int minDetailsLength,
        int maxDetailsLength) {

    public ReportSettings {
        Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(cooldown, "cooldown");
        if (minDetailsLength < 1 || maxDetailsLength < minDetailsLength) {
            throw new IllegalArgumentException("invalid details length bounds");
        }
    }
}
