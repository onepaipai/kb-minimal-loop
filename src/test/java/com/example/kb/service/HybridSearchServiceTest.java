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
import static org.mockito.Mockito.times;
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
        // ILIKE 先给 5 条，模糊兜底不触发（命中少于 5 条才补查，见 fuzzyFallbackRunsWhenIlikeHitsAreFew）
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        new HybridSearchService.RetrievedChunk("x1", "s1.txt", 0),
                        new HybridSearchService.RetrievedChunk("x2", "s2.txt", 0),
                        new HybridSearchService.RetrievedChunk("x3", "s3.txt", 0),
                        new HybridSearchService.RetrievedChunk("x4", "s4.txt", 0),
                        new HybridSearchService.RetrievedChunk("x5", "s5.txt", 0)));
        HybridSearchService service = new HybridSearchService(embeddingStore, jdbcTemplate, disabledRerank());
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
    void searchReranksWhenEnabled() {
        HybridSearchService.RetrievedChunk a = new HybridSearchService.RetrievedChunk("A 文本", "a.txt", 0.9);
        HybridSearchService.RetrievedChunk b = new HybridSearchService.RetrievedChunk("B 文本", "b.txt", 0.8);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(a, b));
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        RerankClient stub = (query, documents, topN) -> List.of(
                new RerankClient.RerankedDoc(1, 0.95),
                new RerankClient.RerankedDoc(0, 0.4));
        HybridSearchService service = new HybridSearchService(embeddingStore, jdbcTemplate,
                new RerankService(properties, stub));
        List<HybridSearchService.RetrievedChunk> result = service.search(
                new Embedding(new float[] {0.1f, 0.2f}),
                MetadataFilterBuilder.metadataKey("visibility").isEqualTo("all"),
                "问题", null, List.of(), 2, 0.55);
        assertEquals("b.txt", result.get(0).source());
        assertEquals(0.95, result.get(0).score());
        assertEquals("a.txt", result.get(1).source());
    }

    /** 重排关闭的真实服务：不调模型，原样返回融合顺序。 */
    private static RerankService disabledRerank() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(false);
        return new RerankService(properties, (query, documents, topN) -> {
            throw new AssertionError("disabled 时不该调模型");
        });
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
    void tokenizeExpandsSynonyms() {
        // 休假→请假、报销→费用报销（synonyms.txt，双向）；基础词优先，仍受 8 词上限管
        List<String> tokens = HybridSearchService.tokenize("我要休假");
        assertTrue(tokens.contains("请假"));
        assertTrue(tokens.contains("休假"));
        assertTrue(tokens.size() <= 8);
        assertTrue(HybridSearchService.tokenize("报销流程").contains("费用报销"));
    }

    @Test
    void fuzzyFallbackRunsWhenIlikeHitsAreFew() {
        HybridSearchService.RetrievedChunk fuzzy =
                new HybridSearchService.RetrievedChunk("请假文本", "leave.txt", 0);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        // 第一次 ILIKE 命中 0 条，触发 pg_trgm 兜底，第二次返回模糊命中
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(), List.of(fuzzy));
        HybridSearchService service = new HybridSearchService(embeddingStore, jdbcTemplate, disabledRerank());
        List<HybridSearchService.RetrievedChunk> result = service.search(
                new Embedding(new float[] {0.1f, 0.2f}),
                MetadataFilterBuilder.metadataKey("visibility").isEqualTo("all"),
                "请加制度", null, List.of(), 5, 0.55);

        assertEquals(1, result.size());
        assertEquals("leave.txt", result.get(0).source());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getAllValues().get(1).contains("word_similarity"));
        assertTrue(sql.getAllValues().get(1).contains("GREATEST"));
    }

    @Test
    void escapeLikeEscapesWildcards() {
        assertEquals("100\\%\\_x\\\\y", HybridSearchService.escapeLike("100%_x\\y"));
    }
}
