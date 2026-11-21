package dev.exceptionloader.reports.common.storage;

import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.common.storage.entity.ReportModel.Fields;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates API query objects into Morphia filters and sort orders.
 *
 * <p>The default view (open reports, newest first) is covered by the {@code status_createdAt}
 * index. Other combinations use one of the single field indexes.
 */
public final class ReportFilters {

    private ReportFilters() {
    }

    public static Filter[] toFilters(ReportFilter filter, Instant now) {
        List<Filter> filters = new ArrayList<>(4);
        if (filter.status() != null) {
            filters.add(Filters.eq(Fields.status, filter.status()));
        }
        if (filter.templateId() != null) {
            filters.add(Filters.eq(Fields.template, filter.templateId()));
        }
        if (filter.server() != null) {
            filters.add(Filters.eq(Fields.server, filter.server()));
        }
        filter.range().since(now).ifPresent(since -> filters.add(Filters.gte(Fields.createdAt, since)));
        return filters.toArray(Filter[]::new);
    }

    public static Sort[] toSort(ReportSort sort) {
        Sort newest = Sort.descending(Fields.createdAt);
        return switch (sort) {
            case NEWEST -> new Sort[] {newest};
            case OLDEST -> new Sort[] {Sort.ascending(Fields.createdAt)};
            case SERVER -> new Sort[] {Sort.ascending(Fields.server), newest};
            case TEMPLATE -> new Sort[] {Sort.ascending(Fields.template), newest};
        };
    }
}
