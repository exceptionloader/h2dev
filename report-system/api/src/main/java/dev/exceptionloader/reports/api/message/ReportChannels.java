package dev.exceptionloader.reports.api.message;

/**
 * Redis Pub/Sub channels used between the server instances.
 */
public final class ReportChannels {

    /** Carries {@link ReportCreatedMessage}. */
    public static final String NEW_REPORT = "reports:new";

    /** Carries {@link ReportStatusMessage}. */
    public static final String STATUS_UPDATE = "reports:status_update";

    /** Carries {@link TemplateReloadMessage}. */
    public static final String TEMPLATE_RELOAD = "reports:templates";

    private ReportChannels() {
    }
}
