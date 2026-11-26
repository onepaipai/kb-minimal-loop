package com.example.kb.config;

import java.time.Duration;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.kb.service.DocumentChunker;

/** DashScope 向量模型与文档切块配置。 */
@Configuration
public class EmbeddingConfig {

    /** 向量维度：向量模型、向量库、schema.sql 三处必须一致。 */
    public static final int EMBEDDING_DIMENSION = 1024;
    /** 文档切块：最大 800 字符、重叠 150 字符（只在句末断开）。500 嫌碎，一条完整制度条款常被拦腰切。 */
    public static final int CHUNK_SIZE = 800;
    public static final int CHUNK_OVERLAP = 150;

    @Value("${langchain4j.dashscope.api-key}")
    private String apiKey;

    @Value("${langchain4j.dashscope.base-url}")
    private String baseUrl;

    @Value("${langchain4j.dashscope.model-name}")
    private String modelName;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .dimensions(EMBEDDING_DIMENSION)
                .timeout(Duration.ofSeconds(15))
                .build();
    }

    @Bean
    public DocumentSplitter documentSplitter() {
        return new DocumentChunker(CHUNK_SIZE, CHUNK_OVERLAP);
    }
}
