package dev.exceptionloader.reports.api.action;

import dev.exceptionloader.reports.api.model.Report;
import org.jetbrains.annotations.NotNull;

/**
 * Outcome of {@link dev.exceptionloader.reports.api.ReportService#decide(String, ReportDecision)}.
 */
public sealed interface DecisionResult {

    /** The report was closed and the reporter will be notified. */
    record Applied(@NotNull Report report) implements DecisionResult {
    }

    /** There is no report with that id. */
    record NotFound(@NotNull String reportId) implements DecisionResult {
    }

    /** Somebody else closed the report first. */
    record AlreadyClosed(@NotNull Report report) implements DecisionResult {
    }

    /** Moderators may not decide on reports filed against themselves. */
    record OwnReport(@NotNull Report report) implements DecisionResult {
    }
}
