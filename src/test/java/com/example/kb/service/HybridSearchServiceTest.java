package com.example.kb.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridSearchServiceTest {

    @Test
    void fusePrefersChunksInBothLists() {
        HybridSearchService.RetrievedChunk a = new HybridSearchService.RetrievedChunk("A 文本", "a.txt", 0.9);
        HybridSearchService.RetrievedChunk b = new HybridSearchService.RetrievedChunk("B 文本", "b.txt", 0.8);
        HybridSearchService.RetrievedChunk c = new HybridSearchService.RetrievedChunk("C 文本", "c.txt", 0.7);
        HybridSearchService.RetrievedChunk d = new HybridSearchService.RetrievedChunk("D 文本", "d.txt", 0);
        List<HybridSearchService.RetrievedChunk> fused = HybridSearchService.fuse(
                List.of(a, b, c), List.of(b, d), 3);
        // B 两路都有（1/61 + 1/61），排第一；A 只有向量分排第二；D 关键词第 2 名（1/62）比 C 向量第 3 名（1/63）分高，排第三
        assertEquals("b.txt", fused.get(0).source());
        assertEquals("a.txt", fused.get(1).source());
        assertEquals("d.txt", fused.get(2).source());
        assertEquals(3, fused.size());
    }

    @Test
    void fuseRespectsMaxResults() {
        List<HybridSearchService.RetrievedChunk> vector = List.of(
                new HybridSearchService.RetrievedChunk("A", "a.txt", 0.9),
                new HybridSearchService.RetrievedChunk("B", "b.txt", 0.8));
        assertEquals(1, HybridSearchService.fuse(vector, List.of(), 1).size());
    }

    @Test
    void fuseDedupesSameChunk() {
        HybridSearchService.RetrievedChunk same = new HybridSearchService.RetrievedChunk("同块", "a.txt", 0.9);
        List<HybridSearchService.RetrievedChunk> fused = HybridSearchService.fuse(
                List.of(same), List.of(same), 5);
        assertEquals(1, fused.size());
    }

    @Test
    void tokenizeChineseUsesBigrams() {
        List<String> tokens = HybridSearchService.tokenize("请假超过3天找谁批？");
        assertTrue(tokens.contains("请假"));
        assertTrue(tokens.contains("找谁"));
        assertTrue(tokens.size() <= 8);
    }

    @Test
    void tokenizeEnglishWords() {
        assertEquals(List.of("how", "apply", "leave"), HybridSearchService.tokenize("how to apply leave?"));
    }

    @Test
    void escapeLikeEscapesWildcards() {
        assertEquals("100\\%\\_x\\\\y", HybridSearchService.escapeLike("100%_x\\y"));
    }
}
