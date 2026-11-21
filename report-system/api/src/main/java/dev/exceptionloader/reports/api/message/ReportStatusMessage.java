package dev.exceptionloader.reports.api.message;

import dev.exceptionloader.reports.api.model.ReportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Published on {@link ReportChannels#STATUS_UPDATE} after a moderator closed a report.
 *
 * <p>The message is a signal, not the source of truth: the instance the reporter is connected to
 * claims the notification in MongoDB before delivering it, which also provides the moderator note.
 *
 * @param reportId     id of the report
 * @param reporterUuid reporter uuid
 * @param reporterName reporter name
 * @param reportedName reported player name
 * @param status       new status
 * @param handledBy    moderator name
 * @param timestamp    time of the decision, serialized as ISO-8601
 */
public record ReportStatusMessage(
        String reportId,
        UUID reporterUuid,
        String reporterName,
        String reportedName,
        ReportStatus status,
        String handledBy,
        Instant timestamp) {
}
