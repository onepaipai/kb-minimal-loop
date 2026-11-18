package com.example.kb.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 引用校验是纯字符串逻辑：有文本依据的引用保留，没依据的只删编号，正文不动。 */
class CitationValidatorTest {

    @Test
    void keepsCitationWhenSentenceSharesWordWithChunk() {
        String answer = "请假超过三天找总经理审批 [1]。";
        String validated = CitationValidator.validate(answer, List.of("请假制度：超过三天找总经理审批"));
        assertEquals(answer, validated);
    }

    @Test
    void dropsCitationWithoutSharedWord() {
        assertEquals("报销贴票当月交财务 。",
                CitationValidator.validate("报销贴票当月交财务 [1]。", List.of("请假制度：年假十五天")));
    }

    @Test
    void dropsOutOfRangeButKeepsUnverifiableShortSentence() {
        // [9] 超范围删掉；“见 [1]”这句本身没有有效词，没东西可核对，不删
        assertEquals("见 [1] 和 。",
                CitationValidator.validate("见 [1] 和 [9]。", List.of("请假制度")));
    }

    @Test
    void checksEachCitationAgainstItsOwnChunk() {
        String answer = "请假找经理 [1]，报销找财务 [2]。";
        List<String> chunks = List.of("请假制度：找经理审批", "年假十五天");
        assertEquals("请假找经理 [1]，报销找财务 。", CitationValidator.validate(answer, chunks));
        List<String> matchingChunks = List.of("请假制度：找经理审批", "报销制度：找财务报销");
        assertEquals(answer, CitationValidator.validate(answer, matchingChunks));
    }

    @Test
    void leavesAnswerWithoutCitationsAlone() {
        assertEquals("没有引用。", CitationValidator.validate("没有引用。", List.of("请假制度")));
        assertEquals("见 [1]。", CitationValidator.validate("见 [1]。", List.of()));
        assertNull(CitationValidator.validate(null, List.of("请假制度")));
    }
}
