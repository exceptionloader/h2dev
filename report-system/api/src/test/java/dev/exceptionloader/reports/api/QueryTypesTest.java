package dev.exceptionloader.reports.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exceptionloader.reports.api.model.StatusCounts;
import dev.exceptionloader.reports.api.query.ReportFilter;
import dev.exceptionloader.reports.api.query.ReportPage;
import dev.exceptionloader.reports.api.query.ReportQuery;
import dev.exceptionloader.reports.api.query.ReportSort;
import dev.exceptionloader.reports.api.query.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTypesTest {

    @Test
    void pageCountRoundsUp() {
        ReportPage page = new ReportPage(List.of(), 0, 10, 21);
        assertEquals(3, page.pageCount());
        assertTrue(page.hasNext());
        assertFalse(page.hasPrevious());
    }

    @Test
    void emptyResultStillHasOnePage() {
        ReportPage page = new ReportPage(List.of(), 0, 10, 0);
        assertEquals(1, page.pageCount());
        assertFalse(page.hasNext());
    }

    @Test
    void queryValidatesPageSize() {
        ReportFilter filter = ReportFilter.openReports();
        assertThrows(IllegalArgumentException.class, () -> new ReportQuery(filter, ReportSort.NEWEST, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReportQuery(filter, ReportSort.NEWEST, -1, 10));
        assertEquals(20, new ReportQuery(filter, ReportSort.NEWEST, 2, 10).offset());
    }

    @Test
    void timeRangeSince() {
        Instant now = Instant.parse("2025-01-10T12:00:00Z");
        assertTrue(TimeRange.ALL.since(now).isEmpty());
        assertEquals(now.minus(Duration.ofDays(7)), TimeRange.LAST_WEEK.since(now).orElseThrow());
    }

    @Test
    void acceptanceRateIgnoresOpenReports() {
        assertEquals(0.75, new StatusCounts(10, 3, 1).acceptanceRate(), 1e-9);
        assertEquals(0, StatusCounts.EMPTY.acceptanceRate());
    }
}
