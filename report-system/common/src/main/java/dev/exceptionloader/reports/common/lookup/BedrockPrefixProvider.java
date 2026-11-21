package dev.exceptionloader.reports.common.lookup;

import org.jetbrains.annotations.NotNull;

/**
 * Supplies the username prefix Floodgate uses for Bedrock players.
 */
@FunctionalInterface
public interface BedrockPrefixProvider {

    /**
     * @return the current prefix, empty if Bedrock names are not prefixed
     */
    @NotNull String bedrockPrefix();
}
