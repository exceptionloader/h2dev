package dev.exceptionloader.reports.api;

import dev.exceptionloader.reports.api.lookup.LookupResult;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves player names to profiles, including players that are not online.
 *
 * <p>Names starting with the Floodgate prefix are looked up as Bedrock accounts. Results are
 * cached, concurrent lookups of the same name share one request.
 */
public interface ProfileService {

    /**
     * @param name player name as typed by a user
     * @return the lookup outcome; the future does not fail
     */
    @NotNull CompletableFuture<LookupResult> lookup(@NotNull String name);
}
