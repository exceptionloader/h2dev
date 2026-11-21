package dev.exceptionloader.reports.api.model;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Report statistics of a single player.
 *
 * @param player   the player
 * @param received reports filed against the player
 * @param filed    reports the player created
 */
public record PlayerStatistics(@NotNull UUID player, @NotNull StatusCounts received, @NotNull StatusCounts filed) {
}
