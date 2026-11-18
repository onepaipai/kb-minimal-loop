package com.example.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 流式引用过滤器：超范围的 [n] 在发出去之前删掉，范围内的保留；与整段版 sanitizeCitations 同规则。 */
class StreamingCitationFilterTest {

    @Test
    void dropsOutOfRangeKeepsInRange() {
        StreamingCitationFilter filter = new StreamingCitationFilter(3);
        assertEquals("见好", filter.feed("见[9]好"));
        assertEquals("见[1][3]好", filter.feed("见[1][3]好"));
        assertEquals("", filter.flush());
    }

    @Test
    void citationSplitAcrossTokens() {
        StreamingCitationFilter filter = new StreamingCitationFilter(3);
        assertEquals("答案", filter.feed("答案["));
        assertEquals("", filter.feed("9"));
        assertEquals("结束", filter.feed("]结束"));
        assertEquals("", filter.flush());

        StreamingCitationFilter kept = new StreamingCitationFilter(3);
        assertEquals("答案", kept.feed("答案["));
        assertEquals("", kept.feed("1"));
        assertEquals("[1]结束", kept.feed("]结束"));
        assertEquals("", kept.flush());
    }

    @Test
    void unclosedTailFlushedLiterally() {
        StreamingCitationFilter filter = new StreamingCitationFilter(3);
        assertEquals("见", filter.feed("见[1"));
        assertEquals("[1", filter.flush());
    }

    @Test
    void nonCitationBracketsKept() {
        StreamingCitationFilter filter = new StreamingCitationFilter(3);
        assertEquals("[12x] [a] []", filter.feed("[12x] [a] []"));
        assertEquals("", filter.flush());
    }

    @Test
    void nullAndEmptyFeedAreSafe() {
        StreamingCitationFilter filter = new StreamingCitationFilter(3);
        assertEquals("", filter.feed(null));
        assertEquals("", filter.feed(""));
        assertEquals("", filter.flush());
    }
}
