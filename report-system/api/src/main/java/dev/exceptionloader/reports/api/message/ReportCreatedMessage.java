package dev.exceptionloader.reports.api.message;

import java.time.Instant;

/**
 * Published on {@link ReportChannels#NEW_REPORT} after a report was stored.
 *
 * @param reportId  id of the report
 * @param reporter  reporter name
 * @param reported  reported player name
 * @param reason    template name or free text
 * @param server    server instance the report was created on
 * @param timestamp creation time, serialized as ISO-8601
 */
public record ReportCreatedMessage(
        String reportId,
        String reporter,
        String reported,
        String reason,
        String server,
        Instant timestamp) {
}
