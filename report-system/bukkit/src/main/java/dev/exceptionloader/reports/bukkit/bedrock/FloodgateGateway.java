package dev.exceptionloader.reports.bukkit.bedrock;

import jakarta.inject.Singleton;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Only loaded when the Floodgate plugin is installed.
 */
@Singleton
public class FloodgateGateway implements BedrockGateway {

    @Override
    public boolean isBedrock(UUID player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player);
    }

    @Override
    public String bedrockPrefix() {
        String prefix = FloodgateApi.getInstance().getPlayerPrefix();
        return prefix == null ? "" : prefix;
    }
}
