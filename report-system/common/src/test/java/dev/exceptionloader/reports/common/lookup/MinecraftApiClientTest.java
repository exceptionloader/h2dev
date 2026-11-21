package dev.exceptionloader.reports.common.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftApiClientTest {

    @Test
    void parsesJavaResponse() {
        ApiResult result = MinecraftApiClient.interpret(200,
                "{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"cachedAt\":1704063600000}", null);
        assertEquals(new ApiResult.Success(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")), result);
    }

    @Test
    void parsesBedrockResponse() {
        ApiResult result = MinecraftApiClient.interpret(200,
                "{\"uuid\":\"00000000-0000-0000-0009-01fb54b26482\",\"xuid\":2535453759792258,\"cachedAt\":1}", null);
        UUID uuid = assertInstanceOf(ApiResult.Success.class, result).uuid();
        assertEquals(0L, uuid.getMostSignificantBits());
        assertEquals(2535453759792258L, uuid.getLeastSignificantBits());
    }

    @Test
    void acceptsUuidsWithoutDashes() {
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                MinecraftApiClient.parseUuidString("069a79f444e94726a5befca90e38aaf5"));
    }

    @Test
    void unknownNames() {
        assertInstanceOf(ApiResult.NotFound.class, MinecraftApiClient.interpret(404, "{\"error\":true}", null));
        assertInstanceOf(ApiResult.NotFound.class, MinecraftApiClient.interpret(400, "{\"error\":true}", null));
    }

    @Test
    void rateLimitCarriesRetryAfter() {
        ApiResult.Failure failure = assertInstanceOf(ApiResult.Failure.class, MinecraftApiClient.interpret(429, "", "12"));
        assertEquals(Duration.ofSeconds(12), failure.retryAfter());
    }

    @Test
    void serverErrorsAndGarbageAreFailures() {
        ApiResult.Failure failure = assertInstanceOf(ApiResult.Failure.class, MinecraftApiClient.interpret(503, "", null));
        assertNull(failure.retryAfter());
        assertInstanceOf(ApiResult.Failure.class, MinecraftApiClient.interpret(200, "<html>", null));
        assertInstanceOf(ApiResult.Failure.class, MinecraftApiClient.interpret(200, "{}", null));
    }
}
