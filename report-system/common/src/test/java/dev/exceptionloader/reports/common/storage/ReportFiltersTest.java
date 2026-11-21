package dev.exceptionloader.reports.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.api.query.TimeRange;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportFiltersTest {

    private final Instant now = Instant.parse("2025-02-01T00:00:00Z");

    @Test
    void emptyFilterMatchesEverything() {
        assertEquals(0, ReportFilters.toFilters(ReportFilter.everything(), now).length);
    }

    @Test
    void everyCriterionBecomesOneFilter() {
        ReportFilter filter = new ReportFilter(ReportStatus.OPEN, "cheating", "lobby-1", TimeRange.LAST_DAY);
        Filter[] filters = ReportFilters.toFilters(filter, now);
        assertEquals(List.of("status", "template", "server", "createdAt"),
                Arrays.stream(filters).map(Filter::getField).toList());
        assertEquals(now.minusSeconds(86_400), filters[3].getValue());
    }

    @Test
    void secondarySortIsAlwaysNewestFirst() {
        Sort[] sort = ReportFilters.toSort(ReportSort.SERVER);
        assertEquals("server", sort[0].getField());
        assertEquals("createdAt", sort[1].getField());
        assertEquals(-1, sort[1].getOrder());
    }
}
