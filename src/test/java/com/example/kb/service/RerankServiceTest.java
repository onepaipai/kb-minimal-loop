package com.example.kb.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 重排服务：模型重排、失败回退、关闭直通，都是纯逻辑，不调真实接口。 */
class RerankServiceTest {

    private static List<HybridSearchService.RetrievedChunk> chunks() {
        return List.of(
                new HybridSearchService.RetrievedChunk("A 文本", "a.txt", 0.03),
                new HybridSearchService.RetrievedChunk("B 文本", "b.txt", 0.02),
                new HybridSearchService.RetrievedChunk("C 文本", "c.txt", 0.01));
    }

    @Test
    void rerankReordersByModelScore() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        RerankClient stub = (query, documents, topN) -> List.of(
                new RerankClient.RerankedDoc(2, 0.95),
                new RerankClient.RerankedDoc(0, 0.4));
        RerankService service = new RerankService(properties, stub);
        List<HybridSearchService.RetrievedChunk> reranked = service.rerank("问题", chunks(), 2);
        assertEquals("c.txt", reranked.get(0).source());
        assertEquals(0.95, reranked.get(0).score());
        assertEquals("a.txt", reranked.get(1).source());
        assertEquals(2, reranked.size());
    }

    @Test
    void rerankFallsBackOnClientFailure() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(true);
        RerankClient failing = (query, documents, topN) -> {
            throw new RuntimeException("timeout");
        };
        RerankService service = new RerankService(properties, failing);
        List<HybridSearchService.RetrievedChunk> reranked = service.rerank("问题", chunks(), 2);
        assertEquals("a.txt", reranked.get(0).source());
        assertEquals("b.txt", reranked.get(1).source());
    }

    @Test
    void rerankDisabledPassesThrough() {
        RerankProperties properties = new RerankProperties();
        properties.setEnabled(false);
        RerankClient neverCalled = (query, documents, topN) -> {
            throw new AssertionError("disabled 时不该调模型");
        };
        RerankService service = new RerankService(properties, neverCalled);
        List<HybridSearchService.RetrievedChunk> input = chunks();
        assertEquals(input.subList(0, 2), service.rerank("问题", input, 2));
    }
}
