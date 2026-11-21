package dev.exceptionloader.reports.bukkit.bedrock;

import dev.exceptionloader.reports.api.model.Edition;
import dev.exceptionloader.reports.common.lookup.BedrockPrefixProvider;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Access to Floodgate without a hard dependency on it.
 */
public interface BedrockGateway extends BedrockPrefixProvider {

    boolean isBedrock(@NotNull UUID player);

    default @NotNull Edition editionOf(@NotNull UUID player) {
        return isBedrock(player) ? Edition.BEDROCK : Edition.JAVA;
    }
}
