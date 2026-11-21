package dev.exceptionloader.reports.api.action;

import dev.exceptionloader.reports.api.model.PlayerProfile;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything needed to file a report.
 *
 * @param reporter   player filing the report
 * @param reported   player being reported
 * @param templateId chosen template
 * @param details    free text, required if the template demands one
 */
public record ReportRequest(
        @NotNull PlayerProfile reporter,
        @NotNull PlayerProfile reported,
        @NotNull String templateId,
        @Nullable String details) {

    public ReportRequest {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(reported, "reported");
        Objects.requireNonNull(templateId, "templateId");
    }
}
