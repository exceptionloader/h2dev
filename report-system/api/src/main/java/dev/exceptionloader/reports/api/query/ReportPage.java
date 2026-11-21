package dev.exceptionloader.reports.api.query;

import dev.exceptionloader.reports.api.model.Report;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One page of a report listing.
 *
 * @param reports  reports on this page
 * @param page     zero based page index
 * @param pageSize requested page size
 * @param total    number of reports matching the filter
 */
public record ReportPage(@NotNull List<Report> reports, int page, int pageSize, long total) {

    public ReportPage {
        reports = List.copyOf(reports);
    }

    public int pageCount() {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < pageCount();
    }
}
