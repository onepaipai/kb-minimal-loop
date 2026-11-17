package com.example.kb.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 混合检索：向量检索 + 关键词检索（SQL ILIKE），用 RRF（倒数排名融合）合并取前 N。
 * 向量分只看语义，关键词分只看字面命中，两路互补：问法换了词、向量分不够时关键词能兜底。
 * 返回的 score 是 RRF 融合分（不是余弦相似度），越大越相关；minScore 只卡向量那一路。
 */
@Service
public class HybridSearchService {

    /** RRF 常数 k：标准取值 60 */
    private static final int RRF_K = 60;
    /** 关键词检索最多取的候选块数 */
    private static final int KEYWORD_LIMIT = 20;
    /** 向量检索多取一倍做融合候选，最后仍只返回 maxResults 条 */
    private static final int VECTOR_OVERSAMPLE = 2;
    /** 分词后最多用的词数，太多词的 ILIKE 全是噪音 */
    private static final int MAX_TOKENS = 8;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;

    public HybridSearchService(EmbeddingStore<TextSegment> embeddingStore, JdbcTemplate jdbcTemplate) {
        this.embeddingStore = embeddingStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record RetrievedChunk(String text, String source, double score) {
    }

    /**
     * 两路检索再融合：向量路按 filter（可见性 + 状态 + 来源）查，关键词路用同样的权限条件查 SQL。
     * department 为 null 表示只查全员可见；sources 为空表示不限来源。
     */
    public List<RetrievedChunk> search(Embedding queryEmbedding, Filter filter, String question, String department,
                                       List<String> sources, int maxResults, double minScore) {
        List<RetrievedChunk> vectorRanked = searchVector(queryEmbedding, filter, maxResults, minScore);
        List<RetrievedChunk> keywordRanked = searchKeyword(question, department, sources);
        return fuse(vectorRanked, keywordRanked, maxResults);
    }

    private List<RetrievedChunk> searchVector(Embedding queryEmbedding, Filter filter, int maxResults, double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.min(maxResults * VECTOR_OVERSAMPLE, KEYWORD_LIMIT))
                .minScore(minScore)
                .filter(filter)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        List<RetrievedChunk> ranked = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            ranked.add(new RetrievedChunk(segment.text(), segment.metadata().getString(MetadataKeys.SOURCE), match.score()));
        }
        return ranked;
    }

    /**
     * 关键词检索：问题分词后 text ILIKE 命中，按命中词数排序；权限条件与向量路一致，
     * 且只查已发布（status 缺失的老块按已发布算，与清单逻辑一致）。
     */
    private List<RetrievedChunk> searchKeyword(String question, String department, List<String> sources) {
        List<String> tokens = tokenize(question);
        if (tokens.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT text, metadata->>'source' AS source FROM chunk_embedding WHERE metadata->>'source' IS NOT NULL "
                        + "AND COALESCE(metadata->>'status','approved') = 'approved' AND ");
        List<Object> params = new ArrayList<>();
        if (department == null) {
            sql.append("metadata->>'visibility' = ? AND ");
            params.add("all");
        } else {
            sql.append("metadata->>'visibility' IN (?, ?) AND ");
            params.add("all");
            params.add(department);
        }
        if (sources != null && !sources.isEmpty()) {
            sql.append("metadata->>'source' IN (");
            appendPlaceholders(sql, sources.size());
            sql.append(") AND ");
            params.addAll(sources);
        }
        sql.append("(");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("text ILIKE ?");
            params.add("%" + escapeLike(tokens.get(i)) + "%");
        }
        sql.append(") ORDER BY ");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" + ");
            }
            sql.append("(CASE WHEN text ILIKE ? THEN 1 ELSE 0 END)");
            params.add("%" + escapeLike(tokens.get(i)) + "%");
        }
        sql.append(" DESC LIMIT ?");
        params.add(KEYWORD_LIMIT);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                rs.getString("text"), rs.getString("source"), 0), params.toArray());
    }

    /**
     * 分词：按非字母数字切；中文没有空格，连续汉字按二字滑动切（请假→请假/假超…），否则整词匹配不到。
     * 单字太泛不要，最多留 8 个词。
     */
    static List<String> tokenize(String question) {
        List<String> tokens = new ArrayList<>();
        if (question == null) {
            return tokens;
        }
        for (String part : question.split("[^\\p{L}\\p{N}]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (containsHan(part)) {
                addBigrams(tokens, part);
            } else if (part.length() >= 3) {
                // 英文词至少 3 字母：to/is/of 这类二字母词 ILIKE 命中太多，全是噪音
                addToken(tokens, part);
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        return tokens;
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static void addBigrams(List<String> tokens, String text) {
        for (int i = 0; i + 1 < text.length() && tokens.size() < MAX_TOKENS; i++) {
            addToken(tokens, text.substring(i, i + 2));
        }
    }

    private static void addToken(List<String> tokens, String token) {
        if (!tokens.contains(token)) {
            tokens.add(token);
        }
    }

    /** ILIKE 通配符转义：% _ \ 前加反斜杠。 */
    static String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }

    /**
     * RRF 融合：每路按排名给分 1/(60+排名)，同一块两路分数相加，按总分取前 N。
     * 纯函数，可单测；score 是融合分（约 0-0.03），只用于排序与展示，不再是余弦相似度。
     */
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
