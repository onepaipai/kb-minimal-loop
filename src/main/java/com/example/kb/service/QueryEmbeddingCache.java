package com.example.kb.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 问题向量短 TTL 缓存：同一问题在短时间内重复提问（重新生成、多设备重试）不再调 DashScope。
 * 纯内存、容量有上限，不引入缓存框架；向量对象不可变，复用无副作用。
 */
public class QueryEmbeddingCache {

    private record Cached(Embedding embedding, long expireAt) {
    }

    private static final long TTL_MILLIS = 30_000;
    private static final int MAX_SIZE = 256;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final EmbeddingModel embeddingModel;

    public QueryEmbeddingCache(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** 按去空格后的问题取向量：命中且未过期直接复用，否则向量化后写入。 */
    public Embedding embed(String question) {
        String key = question == null ? "" : question.trim();
        long now = System.currentTimeMillis();
        Cached cached = cache.get(key);
        if (cached != null && cached.expireAt() > now) {
            return cached.embedding();
        }
        Embedding embedding = embeddingModel.embed(key).content();
        if (cache.size() >= MAX_SIZE) {
            cache.clear();
        }
        cache.put(key, new Cached(embedding, now + TTL_MILLIS));
        return embedding;
    }
}
