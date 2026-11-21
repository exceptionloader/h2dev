package dev.exceptionloader.reports.bukkit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FormatsTest {

    @Test
    void durationsAreRoundedUp() {
        assertEquals("1s", Formats.duration(Duration.ofMillis(200)));
        assertEquals("45s", Formats.duration(Duration.ofMillis(44_100)));
        assertEquals("1m", Formats.duration(Duration.ofSeconds(60)));
        assertEquals("2m 5s", Formats.duration(Duration.ofSeconds(125)));
    }

    @Test
    void shortIdUsesTheLastCharacters() {
        assertEquals("27b881d4", Formats.shortId("6b00f402e8f7642827b881d4"));
        assertEquals("abc", Formats.shortId("abc"));
    }
}
