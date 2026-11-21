package dev.exceptionloader.reports.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exceptionloader.reports.api.message.ReportCreatedMessage;
import dev.exceptionloader.reports.api.message.ReportStatusMessage;
import dev.exceptionloader.reports.api.model.ReportStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec();

    @Test
    void createdMessageMatchesWireFormat() {
        ReportCreatedMessage message = new ReportCreatedMessage(
                "65a1f0c2e4b0a1b2c3d4e5f6", "Alex", "Steve", "CHEATING", "survival-1", Instant.parse("2025-01-12T18:30:00Z"));
        String json = codec.encode(message);
        assertTrue(json.contains("\"timestamp\":\"2025-01-12T18:30:00Z\""), json);
        assertTrue(json.contains("\"reportId\":\"65a1f0c2e4b0a1b2c3d4e5f6\""), json);
        assertEquals(message, codec.decode(json, ReportCreatedMessage.class));
    }

    @Test
    void statusMessageRoundTrip() {
        ReportStatusMessage message = new ReportStatusMessage(
                "65a1f0c2e4b0a1b2c3d4e5f6", UUID.randomUUID(), "Alex", "Steve", ReportStatus.REJECTED, "Mod", Instant.now());
        String json = codec.encode(message);
        assertTrue(json.contains("\"status\":\"REJECTED\""), json);
        assertEquals(message, codec.decode(json, ReportStatusMessage.class));
    }
}
