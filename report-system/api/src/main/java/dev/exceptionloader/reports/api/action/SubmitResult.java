package dev.exceptionloader.reports.api.action;

import dev.exceptionloader.reports.api.model.Report;
import java.time.Duration;
import org.jetbrains.annotations.NotNull;

/**
 * Outcome of {@link dev.exceptionloader.reports.api.ReportService#submit(ReportRequest)}.
 */
public sealed interface SubmitResult {

    /** The report was stored and announced to the network. */
    record Created(@NotNull Report report) implements SubmitResult {
    }

    /** The reporter already has an open report against this player. */
    record AlreadyReported(@NotNull Report existing) implements SubmitResult {
    }

    /** The reporter has to wait before filing another report. */
    record Cooldown(@NotNull Duration remaining) implements SubmitResult {
    }

    /** The reporter reached the maximum number of open reports. */
    record TooManyOpen(int limit) implements SubmitResult {
    }

    /** The request itself is not acceptable. */
    record Invalid(@NotNull Violation violation) implements SubmitResult {
    }

    enum Violation {
        SELF_REPORT,
        UNKNOWN_TEMPLATE,
        MISSING_DETAILS,
        DETAILS_TOO_SHORT,
        DETAILS_TOO_LONG
    }
}
