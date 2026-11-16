package com.example.kb.config;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DashScope 向量模型与文档切块配置。 */
@Configuration
public class EmbeddingConfig {

    /** 向量维度：向量模型、向量库、schema.sql 三处必须一致。 */
    public static final int EMBEDDING_DIMENSION = 1024;
    /** 文档切块：最大 500 字符、重叠 100 字符。 */
    public static final int CHUNK_SIZE = 500;
    public static final int CHUNK_OVERLAP = 100;

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
                .build();
    }

    @Bean
    public DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
    }
}
