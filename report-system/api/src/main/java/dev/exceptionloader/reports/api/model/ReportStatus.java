package dev.exceptionloader.reports.api.model;

/**
 * Lifecycle state of a report.
 */
public enum ReportStatus {

    /** Waiting for a moderator. */
    OPEN,

    /** A moderator confirmed the report. */
    RESOLVED,

    /** A moderator dismissed the report. */
    REJECTED;

    /**
     * @return {@code true} once a moderator has made a decision
     */
    public boolean isClosed() {
        return this != OPEN;
    }
}
