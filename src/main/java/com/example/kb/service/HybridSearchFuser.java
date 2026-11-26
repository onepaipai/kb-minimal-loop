package com.example.kb.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.kb.service.HybridSearchService.RetrievedChunk;

final class HybridSearchFuser {

    private static final int RRF_K = 60;

    private HybridSearchFuser() {
    }

    static List<RetrievedChunk> fuse(List<RetrievedChunk> vectorRanked, List<RetrievedChunk> keywordRanked,
                                     int maxResults) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        accumulate(vectorRanked, merged, scores);
        accumulate(keywordRanked, merged, scores);
        List<RetrievedChunk> fused = new ArrayList<>();
        for (Map.Entry<String, RetrievedChunk> entry : merged.entrySet()) {
            RetrievedChunk chunk = entry.getValue();
            fused.add(new RetrievedChunk(chunk.text(), chunk.source(), scores.get(entry.getKey())));
        }
        fused.sort((a, b) -> Double.compare(b.score(), a.score()));
        return fused.subList(0, Math.min(maxResults, fused.size()));
    }

    private static void accumulate(List<RetrievedChunk> ranked, Map<String, RetrievedChunk> merged,
                                   Map<String, Double> scores) {
        for (int i = 0; i < ranked.size(); i++) {
            RetrievedChunk chunk = ranked.get(i);
            String key = chunk.source() + "\u0000" + chunk.text();
            merged.putIfAbsent(key, chunk);
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }
}
