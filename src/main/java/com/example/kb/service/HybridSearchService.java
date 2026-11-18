package com.example.kb.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** RRF 常数 k：标准取值 60 */
    private static final int RRF_K = 60;
    /** 关键词检索最多取的候选块数 */
    private static final int KEYWORD_LIMIT = 20;
    /** 向量检索多取一倍做融合候选，最后仍只返回 maxResults 条 */
    private static final int VECTOR_OVERSAMPLE = 2;
    /** 分词后最多用的词数，太多词的 ILIKE 全是噪音 */
    private static final int MAX_TOKENS = 8;
    /** 重排开启时融合先取的候选数，重排后再取前 maxResults 条 */
    private static final int RERANK_CANDIDATES = 20;
    /** ILIKE 命中少于 5 条时才跑 pg_trgm 模糊兜底（问答只取 5 块，关键词候选够 5 条就没必要再模糊） */
    private static final int FUZZY_MIN_ILIKE_HITS = 5;
    /** 模糊兜底最多补的块数 */
    private static final int FUZZY_LIMIT = 5;
    /** word_similarity 阈值 0.2：中文二字词错 1 字得 0.33、三字词错 1 字得 0.25，默认 0.3 会漏掉三字词的错别字 */
    private static final double FUZZY_SIMILARITY_THRESHOLD = 0.2;
    /** 同义词表：启动时从 classpath 的 synonyms.txt 加载（词=同义词1,同义词2，双向生效）；加载失败记日志、用空表 */
    private static final Map<String, List<String>> SYNONYMS = loadSynonyms();

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;
    private final RerankService rerankService;

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
        List<RetrievedChunk> vectorRanked = searchVector(queryEmbedding, filter, maxResults, minScore);
        List<RetrievedChunk> keywordRanked = searchKeyword(question, department, sources);
        // 重排开启时融合多取候选再重排取前 N；关闭时直接取前 N，重排服务原样返回，不多一次调用
        int fuseLimit = rerankService.isEnabled() ? RERANK_CANDIDATES : maxResults;
        List<RetrievedChunk> fused = fuse(vectorRanked, keywordRanked, fuseLimit);
        return rerankService.rerank(question, fused, maxResults);
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
        appendPermissionConditions(sql, params, department, sources);
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
        params.add(KEYWORD_LIMIT);
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
            appendPermissionConditions(sql, params, department, sources);
            sql.append("(");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("word_similarity(?, text) >= ?");
                params.add(tokens.get(i));
                params.add(FUZZY_SIMILARITY_THRESHOLD);
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
            params.add(FUZZY_LIMIT);
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RetrievedChunk(
                    rs.getString("text"), rs.getString("source"), 0), params.toArray());
        } catch (Exception e) {
            log.warn("模糊检索失败，跳过兜底：{}", e.toString());
            return List.of();
        }
    }

    /** 两路关键词 SQL 共用的权限条件：可见性 + 已发布 + 来源限定；条件与向量路一致。 */
    private static void appendPermissionConditions(StringBuilder sql, List<Object> params, String department,
                                                   List<String> sources) {
        sql.append("metadata->>'source' IS NOT NULL AND COALESCE(metadata->>'status','approved') = 'approved' AND ");
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
    }

    /**
     * 分词：按非字母数字切；中文没有空格，连续汉字按二字滑动切（请假→请假/假超…），否则整词匹配不到。
     * 单字太泛不要，最多留 8 个词；分完再扩同义词（基础词优先，仍受 8 词上限管）。
     */
    static List<String> tokenize(String question) {
        List<String> tokens = splitWords(question, 3, MAX_TOKENS);
        expandSynonyms(tokens);
        return tokens;
    }

    /**
     * 按词切：去重、按出现顺序、最多留 limit 个；英文词至少 minLatinLength 字母
     * （分词用 3：to/is/of 这类二字母词 ILIKE 命中太多，全是噪音）。
     */
    static List<String> splitWords(String text, int minLatinLength, int limit) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String part : text.split("[^\\p{L}\\p{N}]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (containsHan(part)) {
                addBigrams(words, part, limit);
            } else if (part.length() >= minLatinLength) {
                addToken(words, part);
            }
            if (words.size() >= limit) {
                break;
            }
        }
        return words;
    }

    /** 同义词扩展：基础词的同义词追加进分词（基础词优先，仍受 8 词上限管）。 */
    private static void expandSynonyms(List<String> tokens) {
        List<String> additions = new ArrayList<>();
        for (String token : tokens) {
            additions.addAll(SYNONYMS.getOrDefault(token, List.of()));
        }
        for (String synonym : additions) {
            if (tokens.size() >= MAX_TOKENS) {
                return;
            }
            addToken(tokens, synonym);
        }
    }

    /** 同义词表加载：每行“词=同义词1,同义词2”，双向生效；# 开头与空行跳过，格式不对的行跳过。 */
    static Map<String, List<String>> loadSynonyms() {
        Map<String, List<String>> synonyms = new LinkedHashMap<>();
        try (InputStream in = HybridSearchService.class.getResourceAsStream("/synonyms.txt")) {
            if (in == null) {
                log.warn("同义词表 synonyms.txt 不存在，分词不扩展");
                return synonyms;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                parseSynonymLine(line, synonyms);
            }
        } catch (IOException e) {
            log.warn("同义词表加载失败，分词不扩展：{}", e.toString());
        }
        return synonyms;
    }

    private static void parseSynonymLine(String line, Map<String, List<String>> synonyms) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        String[] sides = trimmed.split("=", 2);
        if (sides.length != 2) {
            return;
        }
        List<String> group = new ArrayList<>();
        group.add(sides[0].trim());
        for (String synonym : sides[1].split("[,，]")) {
            if (!synonym.isBlank()) {
                group.add(synonym.trim());
            }
        }
        if (group.size() < 2) {
            return;
        }
        for (String word : group) {
            List<String> others = new ArrayList<>(group);
            others.remove(word);
            synonyms.put(word, others);
        }
    }

    private static boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static void addBigrams(List<String> tokens, String text, int limit) {
        for (int i = 0; i + 1 < text.length() && tokens.size() < limit; i++) {
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
