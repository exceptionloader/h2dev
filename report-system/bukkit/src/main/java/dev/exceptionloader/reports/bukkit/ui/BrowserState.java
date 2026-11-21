package dev.exceptionloader.reports.bukkit.ui;

import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportQuery;
import dev.exceptionloader.reports.api.query.ReportSort;

/**
 * Filter, sort and page of the report browser, kept while moderators jump between list and details.
 */
public record BrowserState(ReportFilter filter, ReportSort sort, int page) {

    public static BrowserState initial() {
        return new BrowserState(ReportFilter.openReports(), ReportSort.NEWEST, 0);
    }

    public BrowserState withFilter(ReportFilter filter) {
        return new BrowserState(filter, sort, 0);
    }

    public BrowserState withSort(ReportSort sort) {
        return new BrowserState(filter, sort, 0);
    }

    public BrowserState withPage(int page) {
        return new BrowserState(filter, sort, Math.max(0, page));
    }

    public ReportQuery query(int pageSize) {
        return new ReportQuery(filter, sort, page, pageSize);
    }
}
