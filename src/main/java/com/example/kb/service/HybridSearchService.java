package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 混合检索：向量检索 + 关键词检索（SQL ILIKE 候选、词频分排序），用 RRF（倒数排名融合）合并取前 N；
 * 开启重排时先多取 20 个候选，再用 qwen3-rerank 按“是否回答了这个问题”重排取前 N。
 * 向量分只看语义，关键词分只看字面命中，两路互补：问法换了词、向量分不够时关键词能兜底。
 * 分词带同义词扩展（synonyms.txt）；ILIKE 命中不足时 pg_trgm 模糊兜底（错别字、换说法）。
 * 返回的 score 是 RRF 融合分（不是余弦相似度），越大越相关；minScore 只卡向量那一路。重排开启时 score 是重排相关度（0-1）。
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 向量检索多取一倍做融合候选，最后仍只返回 maxResults 条 */
    private static final int VECTOR_OVERSAMPLE = 2;
    /** 重排开启时融合先取的候选数，重排后再取前 maxResults 条；候选越少重排请求体越小、首字越快 */
    private static final int RERANK_CANDIDATES = 10;
    /** ILIKE 命中少于 5 条时才跑 pg_trgm 模糊兜底（问答只取 5 块，关键词候选够 5 条就没必要再模糊） */
    private static final int FUZZY_MIN_ILIKE_HITS = 5;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;
    private final RerankService rerankService;
    /** 向量路与关键词路互不依赖，并发发起砍掉一次串行 DB 往返；守护线程池随 JVM 退出 */
    private final Executor searchExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "hybrid-search-");
        thread.setDaemon(true);
        return thread;
    });

    public HybridSearchService(EmbeddingStore<TextSegment> embeddingStore, JdbcTemplate jdbcTemplate,
                               RerankService rerankService) {
        this.embeddingStore = embeddingStore;
        this.jdbcTemplate = jdbcTemplate;
        this.rerankService = rerankService;
    }

    public record RetrievedChunk(String text, String source, double score) {
    }

    /**
     * 两路检索再融合：向量路按 filter（可见性 + 状态 + 来源）查，关键词路用同样的权限条件查 SQL。
     * department 为 null 表示只查全员可见；sources 为空表示不限来源。
     */
    public List<RetrievedChunk> search(Embedding queryEmbedding, Filter filter, String question, String department,
                                       List<String> sources, int maxResults, double minScore) {
        // 两路互不依赖，并发发起；各自结果与串行完全一致，RRF 参数顺序不变
        CompletableFuture<List<RetrievedChunk>> vectorFuture = CompletableFuture.supplyAsync(
                () -> searchVector(queryEmbedding, filter, maxResults, minScore), searchExecutor);
        CompletableFuture<List<RetrievedChunk>> keywordFuture = CompletableFuture.supplyAsync(
                () -> searchKeyword(question, department, sources), searchExecutor);
        List<RetrievedChunk> vectorRanked = vectorFuture.join();
        List<RetrievedChunk> keywordRanked = keywordFuture.join();
        // 重排开启时融合多取候选再重排取前 N；关闭时直接取前 N，重排服务原样返回，不多一次调用
        int fuseLimit = rerankService.isEnabled() ? RERANK_CANDIDATES : maxResults;
        List<RetrievedChunk> fused = fuse(vectorRanked, keywordRanked, fuseLimit);
        return rerankService.rerank(question, fused, maxResults);
    }

    private List<RetrievedChunk> searchVector(Embedding queryEmbedding, Filter filter, int maxResults, double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.min(maxResults * VECTOR_OVERSAMPLE, HybridSearchSqlBuilder.KEYWORD_LIMIT))
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
     * 关键词检索：问题分词后 text ILIKE 命中，按词频总分排序（同一个词出现 10 次的块比出现 1 次的更相关）；
     * 权限条件与向量路一致，且只查已发布（status 缺失的老块按已发布算，与清单逻辑一致）。
     * ILIKE 命中少于 5 条时 pg_trgm 模糊兜底（错别字、换说法），补在精确命中后面。
     */
    private List<RetrievedChunk> searchKeyword(String question, String department, List<String> sources) {
        List<String> tokens = tokenize(question);
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> hits = new ArrayList<>(searchKeywordIlike(tokens, department, sources));
        if (hits.size() < FUZZY_MIN_ILIKE_HITS) {
            hits.addAll(searchKeywordFuzzy(tokens, department, sources));
        }
        return hits;
    }

    /** ILIKE 精确命中：分词后 text ILIKE 命中，按词频总分排序。 */
    private List<RetrievedChunk> searchKeywordIlike(List<String> tokens, String department, List<String> sources) {
        StringBuilder sql = new StringBuilder(
                "SELECT text, metadata->>'source' AS source FROM chunk_embedding WHERE ");
        List<Object> params = new ArrayList<>();
        HybridSearchSqlBuilder.appendPermissionConditions(sql, params, department, sources);
        sql.append("(");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("text ILIKE ?");
            params.add("%" + escapeLike(tokens.get(i)) + "%");
        }
        sql.append(") ORDER BY ");
        // 词频分：每词在块里出现几次就加几分（出现次数 = 删掉该词前后长度差 / 词长）；只按命中词数排会把出现 1 次和 10 次的块当成一样
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sql.append(" + ");
            }
            sql.append("(LENGTH(text) - LENGTH(REPLACE(LOWER(text), LOWER(?), ''))) / LENGTH(?)");
            params.add(tokens.get(i));
            params.add(tokens.get(i));
        }
        sql.append(" DESC LIMIT ?");
        params.add(HybridSearchSqlBuilder.KEYWORD_LIMIT);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                rs.getString("text"), rs.getString("source"), 0), params.toArray());
    }

    /**
     * pg_trgm 模糊兜底：词与块文本的 word_similarity≥0.2 才算命中，按各词最大相似度排；
     * 出问题（比如扩展未装）时只记日志、返回空，不让兜底拖垮整次提问。
     */
    private List<RetrievedChunk> searchKeywordFuzzy(List<String> tokens, String department, List<String> sources) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT text, metadata->>'source' AS source FROM chunk_embedding WHERE ");
            List<Object> params = new ArrayList<>();
            HybridSearchSqlBuilder.appendPermissionConditions(sql, params, department, sources);
            sql.append("(");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("word_similarity(?, text) >= ?");
                params.add(tokens.get(i));
                params.add(HybridSearchSqlBuilder.FUZZY_SIMILARITY_THRESHOLD);
            }
            sql.append(") ORDER BY GREATEST(");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("word_similarity(?, text)");
                params.add(tokens.get(i));
            }
            sql.append(") DESC LIMIT ?");
            params.add(HybridSearchSqlBuilder.FUZZY_LIMIT);
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                    rs.getString("text"), rs.getString("source"), 0), params.toArray());
        } catch (Exception e) {
            log.warn("模糊检索失败，跳过兜底：{}", e.toString());
            return List.of();
        }
    }

    /** 供搜索接口取命中词做高亮：与关键词检索同一套分词（含同义词扩展）。 */
    public static List<String> tokenize(String question) {
        return HybridSearchTokenizer.tokenize(question);
    }

    static List<String> splitWords(String text, int minLatinLength, int limit) {
        return HybridSearchTokenizer.splitWords(text, minLatinLength, limit);
    }

    static Map<String, List<String>> loadSynonyms() {
        return HybridSearchTokenizer.loadSynonyms();
    }

    static String escapeLike(String token) {
        return HybridSearchTokenizer.escapeLike(token);
    }

    static List<RetrievedChunk> fuse(List<RetrievedChunk> vectorRanked, List<RetrievedChunk> keywordRanked,
                                     int maxResults) {
        return HybridSearchFuser.fuse(vectorRanked, keywordRanked, maxResults);
    }
}
