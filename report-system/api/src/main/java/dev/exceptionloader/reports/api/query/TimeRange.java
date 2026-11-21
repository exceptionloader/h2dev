package dev.exceptionloader.reports.api.query;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Creation time window used to filter reports.
 */
public enum TimeRange {
    ALL(null),
    LAST_DAY(Duration.ofDays(1)),
    LAST_WEEK(Duration.ofDays(7)),
    LAST_MONTH(Duration.ofDays(30));

    private final Duration length;

    TimeRange(@Nullable Duration length) {
        this.length = length;
    }

    /**
     * @param now reference point
     * @return the earliest creation time that still matches, empty for {@link #ALL}
     */
    public @NotNull Optional<Instant> since(@NotNull Instant now) {
        return length == null ? Optional.empty() : Optional.of(now.minus(length));
    }
}
