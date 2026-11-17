package com.example.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 引用清洗是纯字符串逻辑：超出本次引用范围的编号删掉，范围内的不动。 */
class RagServiceTest {

    @Test
    void sanitizeCitationsDropsOutOfRange() {
        assertEquals("见 [1] 和 。", RagService.sanitizeCitations("见 [1] 和 [9]。", 3));
        assertEquals(" 开头", RagService.sanitizeCitations("[0] 开头", 3));
        assertEquals("见 [1][2]。", RagService.sanitizeCitations("见 [1][2]。", 3));
    }

    @Test
    void sanitizeCitationsStripsAllWhenNoHits() {
        assertEquals("见 。", RagService.sanitizeCitations("见 [1]。", 0));
        assertEquals("没有引用。", RagService.sanitizeCitations("没有引用。", 3));
        assertNull(RagService.sanitizeCitations(null, 3));
    }
}
