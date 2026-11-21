package dev.exceptionloader.reports.bukkit.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TemplateMenuTest {

    @Test
    void oneTemplateRowPlusHeaderAndFooter() {
        assertEquals(3, TemplateMenu.rowsFor(0));
        assertEquals(3, TemplateMenu.rowsFor(6));
        assertEquals(3, TemplateMenu.rowsFor(7));
        assertEquals(4, TemplateMenu.rowsFor(8));
    }

    @Test
    void neverLargerThanADoubleChest() {
        assertEquals(6, TemplateMenu.rowsFor(100));
    }
}
