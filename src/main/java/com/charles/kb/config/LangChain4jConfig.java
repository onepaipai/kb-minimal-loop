package com.charles.kb.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.splitter.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 只配 4 个 Bean，对应 RAG 的 4 个零件：
 * 向量模型 / 聊天模型 / 切块器 / 向量库。
 * 远程 API 版：本地不跑 Ollama。
 * 聊天走 DeepSeek（OpenAI 兼容），向量走 DashScope text-embedding-v4（OpenAI 兼容）。
 * 想换供应商时只改这里，IngestService 和 RagService 不用动。
 */
@Configuration
public class LangChain4jConfig {

    public static final String TABLE = "chunk_embedding";

    @Bean
    EmbeddingModel embeddingModel(
            @Value("${app.dashscope.base-url}") String baseUrl,
            @Value("${app.dashscope.api-key}") String apiKey,
            @Value("${app.dashscope.embed-model}") String modelName,
            @Value("${app.dashscope.dimension}") int dimension) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .dimensions(dimension) // text-embedding-v4 默认 1024，须与 schema.sql 的 vector(1024) 一致
                .build();
    }

    @Bean
    ChatModel chatModel(
            @Value("${app.deepseek.base-url}") String baseUrl,
            @Value("${app.deepseek.api-key}") String apiKey,
            @Value("${app.deepseek.chat-model}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Bean
    DocumentSplitter documentSplitter(
            @Value("${app.chunk.size}") int size,
            @Value("${app.chunk.overlap}") int overlap) {
        return DocumentSplitters.recursive(size, overlap);
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(DataSource dataSource) {
        // 表由 schema.sql 自己建（createTable=false），扩展 vector 也由 schema.sql 建，
        // 所以这里不让 LangChain4j 再建表、再建扩展。
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(TABLE)
                .dimension(1024) // 必填（LangChain4j 官方文档列为 Required）：须与 text-embedding-v4 和 schema.sql 的 vector(1024) 一致
                .createTable(false)
                .skipCreateVectorExtension(true)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COMBINED_JSONB)
                        .columnDefinitions(List.of("metadata JSONB NULL"))
                        .build())
                .build();
    }
}
