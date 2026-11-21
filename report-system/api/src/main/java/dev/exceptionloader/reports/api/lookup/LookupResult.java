package dev.exceptionloader.reports.api.lookup;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import org.jetbrains.annotations.NotNull;

/**
 * Outcome of a name to profile lookup.
 */
public sealed interface LookupResult {

    record Found(@NotNull PlayerProfile profile) implements LookupResult {
    }

    /** The name is well-formed but no account uses it. */
    record NotFound(@NotNull String name) implements LookupResult {
    }

    /** The name cannot belong to any Minecraft account. */
    record InvalidName(@NotNull String name) implements LookupResult {
    }

    /** The lookup service could not be reached and no fallback knew the player. */
    record Unavailable(@NotNull String name) implements LookupResult {
    }
}
