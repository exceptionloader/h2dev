package dev.exceptionloader.reports.api.action;

import dev.exceptionloader.reports.api.model.ReportStatus;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A moderator decision that closes a report.
 *
 * @param outcome       {@link ReportStatus#RESOLVED} or {@link ReportStatus#REJECTED}
 * @param moderator     moderator uuid, {@code null} for the console
 * @param moderatorName moderator name
 * @param note          reason for the decision, mandatory when rejecting
 */
public record ReportDecision(
        @NotNull ReportStatus outcome,
        @Nullable UUID moderator,
        @NotNull String moderatorName,
        @Nullable String note) {

    public ReportDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(moderatorName, "moderatorName");
        if (outcome == ReportStatus.OPEN) {
            throw new IllegalArgumentException("a decision has to close the report");
        }
        if (outcome == ReportStatus.REJECTED && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("rejecting a report requires a note");
        }
    }

    public static @NotNull ReportDecision resolve(@Nullable UUID moderator, @NotNull String moderatorName) {
        return new ReportDecision(ReportStatus.RESOLVED, moderator, moderatorName, null);
    }

    public static @NotNull ReportDecision reject(@Nullable UUID moderator, @NotNull String moderatorName, @NotNull String note) {
        return new ReportDecision(ReportStatus.REJECTED, moderator, moderatorName, note);
    }
}
