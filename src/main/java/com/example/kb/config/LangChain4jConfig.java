package com.example.kb.config;

import java.util.List;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 统一配置：仅 4 个 Bean（聊天模型、向量模型、切块器、向量库）。
 */
@Configuration
public class LangChain4jConfig {

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

    /** 数据库连接配置，与 spring.datasource 复用同一组环境变量 */
    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

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
     * 向量模型：走 DashScope 的 OpenAI 兼容接口，固定 1024 维。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(dashscopeBaseUrl)
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .dimensions(1024)
                .build();
    }

    /**
     * 文档切块器：最大 500 字符、重叠 100 字符。
     */
    @Bean
    public DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(500, 100);
    }

    /**
     * pgvector 向量库：表结构由 schema.sql 负责，这里只做连接与读写。
     * 1024 维必须与向量模型 dimensions、schema.sql 中 vector(1024) 三处保持一致。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 解析形如 jdbc:postgresql://host:port/dbname?params 的直连地址
        String rest = dbUrl.substring("jdbc:postgresql://".length());
        int slashIndex = rest.indexOf('/');
        String hostPort = rest.substring(0, slashIndex);
        String dbAndParams = rest.substring(slashIndex + 1);
        String host = hostPort;
        int port = 5432;
        int colonIndex = hostPort.indexOf(':');
        if (colonIndex >= 0) {
            host = hostPort.substring(0, colonIndex);
            port = Integer.parseInt(hostPort.substring(colonIndex + 1));
        }
        String database = dbAndParams;
        int paramIndex = dbAndParams.indexOf('?');
        if (paramIndex >= 0) {
            database = dbAndParams.substring(0, paramIndex);
        }
        // COMBINED_JSONB 模式必须显式给出 JSONB 列定义，列名与 schema.sql 保持一致
        MetadataStorageConfig metadataConfig = DefaultMetadataStorageConfig.builder()
                .storageMode(MetadataStorageMode.COMBINED_JSONB)
                .columnDefinitions(List.of("metadata JSONB NULL"))
                .build();
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(dbUser)
                .password(dbPassword)
                .table("chunk_embedding")
                .dimension(1024)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .metadataStorageConfig(metadataConfig)
                .build();
    }
}
