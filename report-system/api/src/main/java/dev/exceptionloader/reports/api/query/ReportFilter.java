package dev.exceptionloader.reports.api.query;

import dev.exceptionloader.reports.api.model.ReportStatus;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Filter criteria for report listings. A {@code null} value means "any".
 *
 * @param status     required status
 * @param templateId required template id
 * @param server     required server instance
 * @param range      creation time window
 */
public record ReportFilter(
        @Nullable ReportStatus status,
        @Nullable String templateId,
        @Nullable String server,
        @NotNull TimeRange range) {

    public ReportFilter {
        Objects.requireNonNull(range, "range");
    }

    /**
     * @return filter matching all open reports
     */
    public static @NotNull ReportFilter openReports() {
        return new ReportFilter(ReportStatus.OPEN, null, null, TimeRange.ALL);
    }

    /**
     * @return filter without any restriction
     */
    public static @NotNull ReportFilter everything() {
        return new ReportFilter(null, null, null, TimeRange.ALL);
    }

    public @NotNull ReportFilter withStatus(@Nullable ReportStatus status) {
        return new ReportFilter(status, templateId, server, range);
    }

    public @NotNull ReportFilter withTemplate(@Nullable String templateId) {
        return new ReportFilter(status, templateId, server, range);
    }

    public @NotNull ReportFilter withServer(@Nullable String server) {
        return new ReportFilter(status, templateId, server, range);
    }

    public @NotNull ReportFilter withRange(@NotNull TimeRange range) {
        return new ReportFilter(status, templateId, server, range);
    }
}
