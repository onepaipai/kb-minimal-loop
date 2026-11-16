package com.example.kb.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class VisibilityTest {

    @Test
    void normalizeLowercases() {
        assertEquals("hr", Visibility.normalize("HR"));
    }

    @Test
    void normalizeAcceptsAll() {
        assertEquals("all", Visibility.normalize("all"));
    }

    @Test
    void normalizeAcceptsHyphenAndUnderscore() {
        assertEquals("hr-1_a", Visibility.normalize("hr-1_a"));
    }

    @Test
    void normalizeRejectsBlank() {
        assertThrows(ResponseStatusException.class, () -> Visibility.normalize(""));
        assertThrows(ResponseStatusException.class, () -> Visibility.normalize("  "));
    }

    @Test
    void normalizeRejectsChineseAndSpaces() {
        assertThrows(ResponseStatusException.class, () -> Visibility.normalize("人事部"));
        assertThrows(ResponseStatusException.class, () -> Visibility.normalize("hr dept"));
    }
}
