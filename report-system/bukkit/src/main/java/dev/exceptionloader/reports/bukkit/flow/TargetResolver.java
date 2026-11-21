package dev.exceptionloader.reports.bukkit.flow;

import dev.exceptionloader.reports.api.ProfileService;
import dev.exceptionloader.reports.api.lookup.LookupResult;
import dev.exceptionloader.reports.api.model.Edition;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.bukkit.bedrock.BedrockGateway;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * Turns a typed name into a profile, trying the cheap local sources before the remote lookup.
 */
@Singleton
public class TargetResolver {

    private final Server server;
    private final ProfileService profiles;
    private final BedrockGateway bedrock;

    @Inject
    public TargetResolver(Server server, ProfileService profiles, BedrockGateway bedrock) {
        this.server = server;
        this.profiles = profiles;
        this.bedrock = bedrock;
    }

    public PlayerProfile profileOf(Player player) {
        return new PlayerProfile(player.getUniqueId(), player.getName(), bedrock.editionOf(player.getUniqueId()));
    }

    /**
     * Must be called on the main thread. Online players and the server's user cache answer
     * immediately, everything else goes to the {@link ProfileService}.
     */
    public CompletableFuture<LookupResult> resolve(String input) {
        String name = input.strip();
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(new LookupResult.Found(profileOf(online)));
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            PlayerProfile profile = new PlayerProfile(cached.getUniqueId(), cached.getName(), editionOf(cached.getUniqueId()));
            return CompletableFuture.completedFuture(new LookupResult.Found(profile));
        }
        return profiles.lookup(name);
    }

    // Floodgate ids carry the XUID in the lower half and zeros in the upper half
    private static Edition editionOf(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 ? Edition.BEDROCK : Edition.JAVA;
    }
}
