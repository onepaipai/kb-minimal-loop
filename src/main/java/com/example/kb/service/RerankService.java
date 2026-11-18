package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 神经重排：RRF 融合后，用重排模型对候选块按“是否回答了这个问题”再排一遍（同义词、错别字、换说法都由模型语义打分，不靠字面命中）。
 * 模型不可用、超时、Key 没权限时原样返回 RRF 顺序，问答不受影响；返回的 score 是重排相关度（0-1），不再是 RRF 融合分。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RerankProperties properties;
    private final RerankClient rerankClient;

    public RerankService(RerankProperties properties, RerankClient rerankClient) {
        this.properties = properties;
        this.rerankClient = rerankClient;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public List<HybridSearchService.RetrievedChunk> rerank(String question,
                                                           List<HybridSearchService.RetrievedChunk> candidates,
                                                           int topN) {
        List<HybridSearchService.RetrievedChunk> fallback = candidates.subList(0, Math.min(topN, candidates.size()));
        if (!properties.isEnabled() || candidates.isEmpty()) {
            return fallback;
        }
        try {
            List<String> documents = candidates.stream()
                    .map(HybridSearchService.RetrievedChunk::text).toList();
            List<RerankClient.RerankedDoc> ranked = rerankClient.rerank(question, documents, topN);
            List<HybridSearchService.RetrievedChunk> reranked = new ArrayList<>();
            for (RerankClient.RerankedDoc doc : ranked) {
                if (doc.index() < 0 || doc.index() >= candidates.size()) {
                    continue;
                }
                HybridSearchService.RetrievedChunk candidate = candidates.get(doc.index());
                reranked.add(new HybridSearchService.RetrievedChunk(
                        candidate.text(), candidate.source(), doc.score()));
            }
            if (reranked.isEmpty()) {
                return fallback;
            }
            return reranked;
        } catch (Exception e) {
            log.warn("重排调用失败，回退 RRF 顺序：{}", e.toString());
            return fallback;
        }
    }
}
