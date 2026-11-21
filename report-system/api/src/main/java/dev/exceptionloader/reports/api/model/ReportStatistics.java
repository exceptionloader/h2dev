package dev.exceptionloader.reports.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Network wide statistics.
 *
 * @param counts       reports per status
 * @param topTemplates most used template ids with their report count, highest first
 */
public record ReportStatistics(@NotNull StatusCounts counts, @NotNull Map<String, Long> topTemplates) {

    public ReportStatistics {
        topTemplates = Collections.unmodifiableMap(new LinkedHashMap<>(topTemplates));
    }
}
