package com.example.kb.config;

import java.util.List;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * pgvector 向量库：复用 Spring Boot 按 spring.datasource.* 建好的 DataSource，
 * DB_URL 里的 ?sslmode=require 等参数完整保留（Neon 直连要求 SSL）；
 * 表结构由 schema.sql 负责，这里只做读写。
 */
@Configuration
public class VectorStoreConfig {

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
                .dimension(EmbeddingConfig.EMBEDDING_DIMENSION)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .metadataStorageConfig(metadataConfig)
                .build();
    }
}
