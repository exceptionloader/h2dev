package dev.exceptionloader.reports.api.query;

/**
 * Sort order for report listings. Ties are always broken by creation time, newest first.
 */
public enum ReportSort {
    NEWEST,
    OLDEST,
    SERVER,
    TEMPLATE
}
