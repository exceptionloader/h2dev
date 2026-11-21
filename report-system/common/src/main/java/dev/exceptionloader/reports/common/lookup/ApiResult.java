package dev.exceptionloader.reports.common.lookup;

import java.time.Duration;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Raw outcome of a single mc-api.io request.
 */
public sealed interface ApiResult {

    record Success(UUID uuid) implements ApiResult {
    }

    record NotFound() implements ApiResult {
    }

    /**
     * @param reason     short description for the log
     * @param retryAfter wait time announced by the API, if any
     */
    record Failure(String reason, @Nullable Duration retryAfter) implements ApiResult {
    }
}
