package dev.exceptionloader.reports.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings for resolving offline players through mc-api.io.
 *
 * @param baseUrl  API base url
 * @param timeout  timeout per request
 * @param cacheTtl how long successful lookups are kept
 * @param missTtl  how long unknown names are remembered
 * @param cooldown how long the API is skipped after it failed or rate limited us
 */
public record LookupSettings(URI baseUrl, Duration timeout, Duration cacheTtl, Duration missTtl, Duration cooldown) {

    public LookupSettings {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        Objects.requireNonNull(missTtl, "missTtl");
        Objects.requireNonNull(cooldown, "cooldown");
    }
}
