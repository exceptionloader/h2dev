package dev.exceptionloader.reports.common.lookup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.exceptionloader.reports.common.config.LookupSettings;
import dev.exceptionloader.reports.common.scheduler.PlatformScheduler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal client for the uuid endpoints of <a href="https://mc-api.io/docs">mc-api.io</a>.
 *
 * <p>{@code GET /uuid/{name}/{edition}} answers {@code {"uuid": "...", "xuid": ..., "cachedAt": ...}}.
 * The HTTP client runs its callbacks on the platform scheduler instead of its default thread pool.
 */
@Singleton
public class MinecraftApiClient implements AutoCloseable {

    private static final String USER_AGENT = "ReportSystem/1.0";
    private static final Pattern UNDASHED_UUID = Pattern.compile("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})");

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration timeout;

    @Inject
    public MinecraftApiClient(LookupSettings settings, PlatformScheduler scheduler) {
        String base = settings.baseUrl().toString();
        this.baseUrl = URI.create(base.endsWith("/") ? base : base + "/");
        this.timeout = settings.timeout();
        this.http = HttpClient.newBuilder()
                .executor(scheduler.async())
                .connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<ApiResult> fetchUuid(NameCandidate candidate) {
        HttpRequest request = HttpRequest.newBuilder(uuidEndpoint(candidate))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> error != null
                        ? new ApiResult.Failure(describe(error), null)
                        : interpret(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(null)));
    }

    URI uuidEndpoint(NameCandidate candidate) {
        String name = URLEncoder.encode(candidate.query(), StandardCharsets.UTF_8).replace("+", "%20");
        return baseUrl.resolve("uuid/" + name + "/" + candidate.edition().name());
    }

    static ApiResult interpret(int status, String body, @Nullable String retryAfter) {
        return switch (status) {
            case 200 -> parseUuid(body);
            // 400: the name cannot exist on that edition
            case 400, 404 -> new ApiResult.NotFound();
            case 429 -> new ApiResult.Failure("rate limited", parseRetryAfter(retryAfter));
            default -> new ApiResult.Failure("HTTP " + status, null);
        };
    }

    static ApiResult parseUuid(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonElement uuid = json.get("uuid");
            if (uuid == null || uuid.isJsonNull()) {
                return new ApiResult.Failure("response without uuid", null);
            }
            return new ApiResult.Success(parseUuidString(uuid.getAsString()));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            return new ApiResult.Failure("malformed response", null);
        }
    }

    static UUID parseUuidString(String value) {
        String dashed = value.indexOf('-') < 0 ? UNDASHED_UUID.matcher(value).replaceFirst("$1-$2-$3-$4-$5") : value;
        return UUID.fromString(dashed);
    }

    private static @Nullable Duration parseRetryAfter(@Nullable String header) {
        if (header == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(1, Long.parseLong(header.strip())));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) {
            return "timeout";
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    @Override
    public void close() {
        http.shutdownNow();
    }
}
