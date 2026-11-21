package dev.exceptionloader.reports.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.exceptionloader.reports.api.action.ReportDecision;
import dev.exceptionloader.reports.api.model.ReportStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportDecisionTest {

    private final UUID moderator = UUID.randomUUID();

    @Test
    void rejectRequiresNote() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportDecision(ReportStatus.REJECTED, moderator, "Mod", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> new ReportDecision(ReportStatus.REJECTED, moderator, "Mod", null));
    }

    @Test
    void decisionCannotReopen() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReportDecision(ReportStatus.OPEN, moderator, "Mod", null));
    }

    @Test
    void resolveWithoutNote() {
        ReportDecision decision = assertDoesNotThrow(() -> ReportDecision.resolve(moderator, "Mod"));
        assertEquals(ReportStatus.RESOLVED, decision.outcome());
    }
}
