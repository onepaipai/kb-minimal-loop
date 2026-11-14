package com.example.kb.service;

import java.util.List;

import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.dto.DocumentSummary;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识库文档管理服务：已入库文档清单与按文档删除。
 */
@Service
public class DocumentManageService {

    private static final String TABLE = "chunk_embedding";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentManageService(JdbcTemplate jdbcTemplate, EmbeddingStore<TextSegment> embeddingStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 按 metadata 里的 source 分组统计已入库文档与块数。
     */
    public DocumentListResponse listDocuments() {
        // 简单聚合在 SQL 层完成，结果组装留在 Java 层
        String sql = "SELECT metadata->>'source' AS source, count(*) AS chunks FROM " + TABLE
                + " WHERE metadata->>'source' IS NOT NULL GROUP BY metadata->>'source' ORDER BY source";
        List<DocumentSummary> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            DocumentSummary summary = new DocumentSummary();
            summary.setSource(rs.getString("source"));
            summary.setChunks(rs.getInt("chunks"));
            return summary;
        });
        DocumentListResponse response = new DocumentListResponse();
        response.setDocuments(documents);
        return response;
    }

    /**
     * 统计指定 source 的已入库块数。Postgres 的 count(*) 返回 bigint，用 Long 接收避免类型窄化问题。
     */
    public int countBySource(String source) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'source' = ?",
                Long.class, source);
        if (count == null) {
            return 0;
        }
        return count.intValue();
    }

    /**
     * 删除指定文档的全部块，deletedChunks 为删除前后统计差值（实际删除量）。
     */
    public DeleteDocumentResponse deleteDocument(String source) {
        int before = countBySource(source);
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(source));
        int deletedChunks = before - countBySource(source);
        DeleteDocumentResponse response = new DeleteDocumentResponse();
        response.setSource(source);
        response.setDeletedChunks(deletedChunks);
        response.setDeleted(deletedChunks > 0);
        return response;
    }
}
