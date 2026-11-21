package dev.exceptionloader.reports.api.model;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of a stored report.
 *
 * @param id            hex representation of the MongoDB object id
 * @param reporter      player who created the report
 * @param reporterName  name of the reporter at creation time
 * @param reported      reported player
 * @param reportedName  name of the reported player at creation time
 * @param templateId    id of the template that was chosen
 * @param reason        template name, or the free text for templates that require one
 * @param status        current status
 * @param handledBy     moderator who closed the report
 * @param handledByName name of that moderator
 * @param modNote       note left by the moderator, mandatory for rejected reports
 * @param server        server instance the report was created on
 * @param createdAt     creation time
 * @param handledAt     time the report was closed
 */
public record Report(
        @NotNull String id,
        @NotNull UUID reporter,
        @NotNull String reporterName,
        @NotNull UUID reported,
        @NotNull String reportedName,
        @NotNull String templateId,
        @NotNull String reason,
        @NotNull ReportStatus status,
        @Nullable UUID handledBy,
        @Nullable String handledByName,
        @Nullable String modNote,
        @NotNull String server,
        @NotNull Instant createdAt,
        @Nullable Instant handledAt) {

    public boolean isOpen() {
        return status == ReportStatus.OPEN;
    }
}
