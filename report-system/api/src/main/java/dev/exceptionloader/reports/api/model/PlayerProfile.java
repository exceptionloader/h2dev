package dev.exceptionloader.reports.api.model;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal identity of a player.
 *
 * @param uuid    unique id; for Bedrock players this is the Floodgate id derived from the XUID
 * @param name    name as shown in game, including the Floodgate prefix for Bedrock players
 * @param edition edition the player plays on
 */
public record PlayerProfile(@NotNull UUID uuid, @NotNull String name, @NotNull Edition edition) {

    public PlayerProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(edition, "edition");
    }

    public static @NotNull PlayerProfile java(@NotNull UUID uuid, @NotNull String name) {
        return new PlayerProfile(uuid, name, Edition.JAVA);
    }
}
