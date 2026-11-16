package com.example.kb.config;

import java.util.List;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * LangChain4j 统一配置：5 个 Bean（聊天模型、流式聊天模型、向量模型、切块器、向量库）。
 */
@Configuration
public class LangChain4jConfig {

    /** 向量维度：embedding 模型、向量库、schema.sql 三处必须一致。 */
    private static final int EMBEDDING_DIMENSION = 1024;
    /** 文档切块：最大 500 字符、重叠 100 字符。 */
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;

    /** DeepSeek 聊天模型配置 */
    @Value("${langchain4j.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${langchain4j.deepseek.base-url}")
    private String deepseekBaseUrl;

    @Value("${langchain4j.deepseek.model-name}")
    private String deepseekModelName;

    /** DashScope 向量模型配置 */
    @Value("${langchain4j.dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${langchain4j.dashscope.base-url}")
    private String dashscopeBaseUrl;

    @Value("${langchain4j.dashscope.model-name}")
    private String dashscopeModelName;

    /**
     * 聊天模型：走 DeepSeek 的 OpenAI 兼容接口。
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .modelName(deepseekModelName)
                .build();
    }

    /**
     * 流式聊天模型：和 chatModel 同一套 DeepSeek 配置，只换流式类，供 /api/ask-stream 逐 token 推送。
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(deepseekBaseUrl)
                .apiKey(deepseekApiKey)
                .modelName(deepseekModelName)
                .build();
    }

    /**
     * 向量模型：走 DashScope 的 OpenAI 兼容接口。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(dashscopeBaseUrl)
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .dimensions(EMBEDDING_DIMENSION)
                .build();
    }

    /**
     * 文档切块器。
     */
    @Bean
    public DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
    }

    /**
     * pgvector 向量库：复用 Spring Boot 按 spring.datasource.* 建好的 DataSource，
     * DB_URL 里的 ?sslmode=require 等参数完整保留（Neon 直连要求 SSL）；
     * 表结构由 schema.sql 负责，这里只做读写。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource) {
        // COMBINED_JSONB 模式必须显式给出 JSONB 列定义，列名与 schema.sql 保持一致
        MetadataStorageConfig metadataConfig = DefaultMetadataStorageConfig.builder()
                .storageMode(MetadataStorageMode.COMBINED_JSONB)
                .columnDefinitions(List.of("metadata JSONB NULL"))
                .build();
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("chunk_embedding")
                .dimension(EMBEDDING_DIMENSION)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .metadataStorageConfig(metadataConfig)
                .build();
    }
}
