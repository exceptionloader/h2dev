package dev.exceptionloader.reports.common.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextInputTest {

    @Test
    void stripsFormattingAndControlCharacters() {
        assertEquals("hacks im pvp", TextInput.clean("§chacks\u0007 &lim   pvp "));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", TextInput.clean(null));
    }

    @Test
    void abbreviate() {
        assertEquals("short", TextInput.abbreviate("short", 10));
        assertEquals("abcd…", TextInput.abbreviate("abcdefghij", 5));
    }
}
