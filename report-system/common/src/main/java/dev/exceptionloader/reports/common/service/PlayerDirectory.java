package dev.exceptionloader.reports.common.service;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import dev.exceptionloader.reports.common.storage.ProfileRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Remembers which uuid belongs to which name for every player that joined the network.
 */
@Singleton
public class PlayerDirectory {

    private final ProfileRepository repository;
    private final PlatformScheduler scheduler;
    private final Clock clock;
    private final Logger logger;

    @Inject
    public PlayerDirectory(ProfileRepository repository, PlatformScheduler scheduler, Clock clock, @PluginLogger Logger logger) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.clock = clock;
        this.logger = logger;
    }

    public void record(PlayerProfile profile) {
        scheduler.runAsync(() -> {
            try {
                repository.save(profile, clock.instant());
            } catch (RuntimeException exception) {
                logger.warning("Could not store profile of " + profile.name() + ": " + exception.getMessage());
            }
        });
    }

    public CompletableFuture<Optional<PlayerProfile>> find(String name) {
        return scheduler.supplyAsync(() -> repository.findByName(name));
    }
}
