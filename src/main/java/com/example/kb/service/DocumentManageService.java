package com.example.kb.service;

import java.util.ArrayList;
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
 * 知识库文档管理服务：已入库文档清单与按文档删除、审批状态与版本号。
 * 状态：pending 待审批、approved 已发布、rejected 已驳回；缺 status 的老块按已发布算（与回填 SQL 一致）。
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
     * includePending 为 false 时只列已发布的（普通用户与编辑的清单）；管理员与部门管理员传 true 看全部，
     * 再用 status 参数筛待审批/已发布/已驳回。未回填 visibility 的旧文档不列出，回填 SQL 见 docs/architecture.md。
     */
    public DocumentListResponse listDocuments(String department, int page, int size,
                                              String status, boolean includePending) {
        String visibilityCondition = buildVisibilityCondition(department);
        List<Object> params = new ArrayList<>(List.of(buildVisibilityParams(department)));
        StringBuilder where = new StringBuilder("metadata->>'source' IS NOT NULL AND " + visibilityCondition);
        // 状态条件：指定了 status 就按它筛（缺字段的老块按 approved 算）；不指定时看调用方要不要待审批的
        if (status != null && !status.isBlank()) {
            where.append(" AND COALESCE(metadata->>'status','approved') = ?");
            params.add(status.trim().toLowerCase());
        } else if (!includePending) {
            where.append(" AND COALESCE(metadata->>'status','approved') = 'approved'");
        }
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'source') FROM " + TABLE + " WHERE " + where,
                Integer.class, params.toArray());
        String sql = "SELECT metadata->>'source' AS source, max(metadata->>'visibility') AS visibility, "
                + "count(*) AS chunks, max(COALESCE(metadata->>'status','approved')) AS status, "
                + "COALESCE(max(CAST(NULLIF(metadata->>'version','') AS INTEGER)), 1) AS version FROM " + TABLE
                + " WHERE " + where
                + " GROUP BY metadata->>'source' ORDER BY source LIMIT ? OFFSET ?";
        params.add(size);
        params.add(page * size);
        List<DocumentSummary> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            DocumentSummary summary = new DocumentSummary();
            summary.setSource(rs.getString("source"));
            summary.setVisibility(rs.getString("visibility"));
            summary.setChunks(rs.getInt("chunks"));
            summary.setStatus(rs.getString("status"));
            summary.setVersion(rs.getInt("version"));
            return summary;
        }, params.toArray());
        DocumentListResponse response = new DocumentListResponse();
        response.setDocuments(documents);
        response.setTotal(total == null ? 0 : total);
        response.setPage(page);
        response.setSize(size);
        return response;
    }

    private static String buildVisibilityCondition(String department) {
        return department == null ? "metadata->>'visibility' = ?" : "metadata->>'visibility' IN (?, ?)";
    }

    private static Object[] buildVisibilityParams(String department) {
        return department == null ? new Object[]{Visibility.ALL} : new Object[]{Visibility.ALL, department};
    }

    /** 某篇文档的可见性（审批与删除的权限判断用）；不存在返回 null。 */
    public String getVisibility(String source) {
        List<String> values = jdbcTemplate.query(
                "SELECT metadata->>'visibility' FROM " + TABLE + " WHERE metadata->>'source' = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 同名替换的下一个版本号：已有最大版本 +1，首版为 1。 */
    public int nextVersion(String source) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(CAST(NULLIF(metadata->>'version','') AS INTEGER)), 0) FROM " + TABLE
                        + " WHERE metadata->>'source' = ?",
                Integer.class, source);
        return (max == null ? 0 : max) + 1;
    }

    /** 审批：整篇文档的块统一改状态，返回改动的块数（0 表示文档不存在）。 */
    public int setStatus(String source, String status) {
        return jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ?", status, source);
    }

    /** 待审批的文档数（总览仪表盘用）：department 为 null 只算全员可见的。 */
    public int countPending(String department) {
        String where = "COALESCE(metadata->>'status','approved') = 'pending' AND " + buildVisibilityCondition(department);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'source') FROM " + TABLE + " WHERE " + where,
                Integer.class, buildVisibilityParams(department));
        return count == null ? 0 : count;
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
