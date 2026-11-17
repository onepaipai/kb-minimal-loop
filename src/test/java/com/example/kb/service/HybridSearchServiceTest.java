package com.example.kb.service;

import java.util.List;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 混合检索：RRF 融合、分词、关键词 SQL 都是纯逻辑或可 mock 的，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void keywordSearchRanksByTermFrequency() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        HybridSearchService service = new HybridSearchService(embeddingStore, jdbcTemplate);
        service.search(new Embedding(new float[] {0.1f, 0.2f}),
                MetadataFilterBuilder.metadataKey("visibility").isEqualTo("all"),
                "请假超过3天找谁批？", null, List.of(), 5, 0.55);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        // 排序用词频（删词前后长度差 / 词长），不再是二值命中数；候选集仍是 ILIKE
        assertTrue(sql.getValue().contains("LENGTH(text) - LENGTH(REPLACE(LOWER(text), LOWER(?), ''))"));
        assertFalse(sql.getValue().contains("CASE WHEN text ILIKE"));
        assertTrue(sql.getValue().contains("text ILIKE ?"));
    }

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
