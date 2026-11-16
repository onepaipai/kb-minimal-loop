package com.example.kb.service;

import java.util.List;

import com.example.kb.common.Visibility;
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
     * 按 metadata 里的 source 分组统计已入库文档与块数，再按 visibility 过滤并分页：
     * 不传 department 只列全员可见的，传了列全员可见加该部门的。
     * 未回填 visibility 的旧文档不列出，回填 SQL 见 docs/architecture.md。
     */
    public DocumentListResponse listDocuments(String department, int page, int size) {
        String visibilityCondition;
        Object[] visibilityParams;
        if (department == null) {
            visibilityCondition = "metadata->>'visibility' = ?";
            visibilityParams = new Object[]{Visibility.ALL};
        } else {
            visibilityCondition = "metadata->>'visibility' IN (?, ?)";
            visibilityParams = new Object[]{Visibility.ALL, department};
        }
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'source') FROM " + TABLE
                        + " WHERE metadata->>'source' IS NOT NULL AND " + visibilityCondition,
                Integer.class, visibilityParams);
        String sql = "SELECT metadata->>'source' AS source, max(metadata->>'visibility') AS visibility, count(*) AS chunks FROM " + TABLE
                + " WHERE metadata->>'source' IS NOT NULL AND " + visibilityCondition
                + " GROUP BY metadata->>'source' ORDER BY source LIMIT ? OFFSET ?";
        Object[] params = new Object[visibilityParams.length + 2];
        System.arraycopy(visibilityParams, 0, params, 0, visibilityParams.length);
        params[visibilityParams.length] = size;
        params[visibilityParams.length + 1] = page * size;
        List<DocumentSummary> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            DocumentSummary summary = new DocumentSummary();
            summary.setSource(rs.getString("source"));
            summary.setVisibility(rs.getString("visibility"));
            summary.setChunks(rs.getInt("chunks"));
            return summary;
        }, params);
        DocumentListResponse response = new DocumentListResponse();
        response.setDocuments(documents);
        response.setTotal(total == null ? 0 : total);
        response.setPage(page);
        response.setSize(size);
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
        removeBySource(source);
        int deletedChunks = before - countBySource(source);
        DeleteDocumentResponse response = new DeleteDocumentResponse();
        response.setSource(source);
        response.setDeletedChunks(deletedChunks);
        response.setDeleted(deletedChunks > 0);
        return response;
    }

    /** 删指定 source 的全部块，供删除接口复用。 */
    public void removeBySource(String source) {
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(MetadataKeys.SOURCE).isEqualTo(source));
    }

    /**
     * 同名替换的第二步：删掉该文件除新批次外的所有旧块。
     * 用 SQL 而不用 Filter：旧块可能没有 batch 字段，Filter 的不等于匹配不到 NULL，COALESCE 才能兜住。
     */
    public int removeReplaced(String source, String keepBatch) {
        return jdbcTemplate.update("DELETE FROM " + TABLE
                + " WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') <> ?", source, keepBatch);
    }
}
