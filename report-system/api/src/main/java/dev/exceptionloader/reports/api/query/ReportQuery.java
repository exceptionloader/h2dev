package dev.exceptionloader.reports.api.query;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * A page request for report listings.
 *
 * @param filter   filter criteria
 * @param sort     sort order
 * @param page     zero based page index
 * @param pageSize reports per page, between 1 and {@value #MAX_PAGE_SIZE}
 */
public record ReportQuery(@NotNull ReportFilter filter, @NotNull ReportSort sort, int page, int pageSize) {

    public static final int MAX_PAGE_SIZE = 100;

    public ReportQuery {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(sort, "sort");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public int offset() {
        return page * pageSize;
    }

    public @NotNull ReportQuery withPage(int page) {
        return new ReportQuery(filter, sort, page, pageSize);
    }
}
