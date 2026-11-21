package dev.exceptionloader.reports.bukkit.bedrock;

import java.util.UUID;

/**
 * Used when Floodgate is missing. Name lookups still honour the configured Bedrock prefix, since
 * Floodgate may run on the proxy only.
 */
public record JavaOnlyGateway(String bedrockPrefix) implements BedrockGateway {

    @Override
    public boolean isBedrock(UUID player) {
        return false;
    }
}
