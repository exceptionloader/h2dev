package dev.exceptionloader.reports.bukkit.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CycleTest {

    private final List<String> options = Arrays.asList(null, "a", "b");

    @Test
    void wrapsAroundInBothDirections() {
        assertEquals("a", Cycle.step(options, null, true));
        assertNull(Cycle.step(options, "b", true));
        assertEquals("b", Cycle.step(options, null, false));
    }

    @Test
    void unknownValueStartsAtTheFirstOption() {
        assertNull(Cycle.step(options, "gone", true));
    }
}
