package dev.exceptionloader.reports.bukkit.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.exceptionloader.reports.api.model.ReportStatus;
import dev.exceptionloader.reports.api.query.ReportSort;
import org.junit.jupiter.api.Test;

class BrowserStateTest {

    @Test
    void initialStateShowsOpenReportsNewestFirst() {
        BrowserState state = BrowserState.initial();
        assertEquals(ReportStatus.OPEN, state.filter().status());
        assertEquals(ReportSort.NEWEST, state.sort());
        assertEquals(0, state.page());
    }

    @Test
    void changingTheFilterOrSortGoesBackToTheFirstPage() {
        BrowserState state = BrowserState.initial().withPage(3);
        assertEquals(0, state.withFilter(state.filter().withServer("lobby-1")).page());
        assertEquals(0, state.withSort(ReportSort.SERVER).page());
    }

    @Test
    void pageNeverBecomesNegative() {
        assertEquals(0, BrowserState.initial().withPage(-1).page());
    }
}
