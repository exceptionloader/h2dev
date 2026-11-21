package dev.exceptionloader.reports.api.message;

import java.time.Instant;

/**
 * Published on {@link ReportChannels#TEMPLATE_RELOAD} when templates changed.
 *
 * @param origin    server instance that triggered the reload
 * @param timestamp time of the change
 */
public record TemplateReloadMessage(String origin, Instant timestamp) {
}
