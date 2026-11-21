package dev.exceptionloader.reports.common.lookup;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.exceptionloader.reports.api.ProfileService;
import dev.exceptionloader.reports.api.lookup.LookupResult;
import dev.exceptionloader.reports.api.model.PlayerProfile;
import dev.exceptionloader.reports.common.config.LookupSettings;
import dev.exceptionloader.reports.common.inject.PluginLogger;
import dev.exceptionloader.reports.common.service.PlayerDirectory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves names of offline players.
 *
 * <p>Order of lookups: in-memory cache, mc-api.io (Java and/or Bedrock depending on the name), and
 * if the API is unreachable the network's own {@link PlayerDirectory}. After a failure or a rate
 * limit answer the API is skipped for a while, so an outage does not slow down every report.
 */
@Singleton
public class DefaultProfileService implements ProfileService {

    private final MinecraftApiClient api;
    private final PlayerDirectory directory;
    private final BedrockPrefixProvider prefixes;
    private final LookupSettings settings;
    private final Clock clock;
    private final Logger logger;

    private final Cache<String, PlayerProfile> profiles;
    private final Cache<String, Boolean> unknownNames;
    private final Map<String, CompletableFuture<LookupResult>> running = new ConcurrentHashMap<>();
    private volatile Instant apiPausedUntil = Instant.MIN;

    @Inject
    public DefaultProfileService(
            MinecraftApiClient api,
            PlayerDirectory directory,
            BedrockPrefixProvider prefixes,
            LookupSettings settings,
            Clock clock,
            @PluginLogger Logger logger) {
        this.api = api;
        this.directory = directory;
        this.prefixes = prefixes;
        this.settings = settings;
        this.clock = clock;
        this.logger = logger;
        this.profiles = CacheBuilder.newBuilder().expireAfterWrite(settings.cacheTtl()).maximumSize(5_000).build();
        this.unknownNames = CacheBuilder.newBuilder().expireAfterWrite(settings.missTtl()).maximumSize(5_000).build();
    }

    @Override
    public CompletableFuture<LookupResult> lookup(String input) {
        String name = input.strip();
        List<NameCandidate> candidates = PlayerNames.candidates(name, prefixes.bedrockPrefix());
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(new LookupResult.InvalidName(name));
        }

        String key = name.toLowerCase(Locale.ROOT);
        PlayerProfile cached = profiles.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(new LookupResult.Found(cached));
        }
        if (unknownNames.getIfPresent(key) != null) {
            return CompletableFuture.completedFuture(new LookupResult.NotFound(name));
        }

        CompletableFuture<LookupResult> created = new CompletableFuture<>();
        CompletableFuture<LookupResult> inFlight = running.putIfAbsent(key, created);
        if (inFlight != null) {
            return inFlight;
        }

        resolve(name, candidates, 0)
                .exceptionally(error -> {
                    logger.log(Level.WARNING, "Lookup of " + name + " failed", error);
                    return new LookupResult.Unavailable(name);
                })
                .thenAccept(result -> {
                    remember(key, result);
                    running.remove(key, created);
                    created.complete(result);
                });
        return created;
    }

    /**
     * Seeds the cache with a profile that is known to be correct, e.g. a player that just joined.
     */
    public void prime(PlayerProfile profile) {
        String key = profile.name().toLowerCase(Locale.ROOT);
        profiles.put(key, profile);
        unknownNames.invalidate(key);
    }

    private CompletableFuture<LookupResult> resolve(String name, List<NameCandidate> candidates, int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.completedFuture(new LookupResult.NotFound(name));
        }
        if (clock.instant().isBefore(apiPausedUntil)) {
            return fromDirectory(name);
        }

        NameCandidate candidate = candidates.get(index);
        return api.fetchUuid(candidate).thenCompose(result -> switch (result) {
            case ApiResult.Success(UUID uuid) ->
                    CompletableFuture.completedFuture(new LookupResult.Found(new PlayerProfile(uuid, name, candidate.edition())));
            case ApiResult.NotFound ignored -> resolve(name, candidates, index + 1);
            case ApiResult.Failure(String reason, Duration retryAfter) -> {
                pauseApi(reason, retryAfter);
                yield fromDirectory(name);
            }
        });
    }

    private CompletableFuture<LookupResult> fromDirectory(String name) {
        return directory.find(name)
                .<LookupResult>thenApply(profile -> profile
                        .<LookupResult>map(LookupResult.Found::new)
                        .orElseGet(() -> new LookupResult.Unavailable(name)))
                .exceptionally(error -> new LookupResult.Unavailable(name));
    }

    private void pauseApi(String reason, @Nullable Duration retryAfter) {
        Duration pause = retryAfter != null ? retryAfter : settings.cooldown();
        apiPausedUntil = clock.instant().plus(pause);
        logger.warning("mc-api.io unavailable (" + reason + "), using the player directory for the next " + pause.toSeconds() + "s");
    }

    private void remember(String key, LookupResult result) {
        switch (result) {
            case LookupResult.Found found -> profiles.put(key, found.profile());
            case LookupResult.NotFound ignored -> unknownNames.put(key, Boolean.TRUE);
            case LookupResult.InvalidName ignored -> {
            }
            case LookupResult.Unavailable ignored -> {
            }
        }
    }
}
