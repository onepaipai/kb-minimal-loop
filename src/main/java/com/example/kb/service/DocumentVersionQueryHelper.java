package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import com.example.kb.common.Visibility;
import com.example.kb.dto.DeleteDocumentResponse;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档版本的关系查询与状态变更。chunk_embedding.metadata 只作为检索兼容投影，
 * 当前版本、候选版本和批次生命周期统一由 document/document_version 维护。
 */
@Component
final class DocumentVersionQueryHelper {

    private static final String TABLE = "chunk_embedding";
    private static final String ACTIVE_STATE = "CURRENT";
    private static final String PENDING_STATE = "PENDING";
    private static final String PROCESSING_STATE = "PROCESSING";
    private static final String SUPERSEDED_STATE = "SUPERSEDED";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;

    DocumentVersionQueryHelper(JdbcTemplate jdbcTemplate, EmbeddingStore<TextSegment> embeddingStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingStore = embeddingStore;
    }

    String buildVisibilityCondition(String department) {
        return department == null ? "metadata->>'visibility' = ?" : "metadata->>'visibility' IN (?, ?)";
    }

    Object[] buildVisibilityParams(String department) {
        return department == null ? new Object[]{Visibility.ALL} : new Object[]{Visibility.ALL, department};
    }

    /** 当前已发布版本的可见性；没有已发布版本时回退到处理中的候选版本。 */
    String getVisibility(String source) {
        VersionRow row = findCurrentVersion(source);
        if (row == null) {
            row = findCandidateVersion(source);
        }
        return row == null ? null : row.visibility();
    }

    /** 待审批候选版本的可见性，审批接口必须使用它而不是已发布版本。 */
    String getCandidateVisibility(String source) {
        VersionRow candidate = findCandidateVersion(source);
        return candidate == null ? getVisibility(source) : candidate.visibility();
    }

    String getVersionVisibility(String source, int version) {
        VersionRow row = findVersion(source, version);
        return row == null ? null : row.visibility();
    }

    String getVersionStatus(String source, int version) {
        VersionRow row = findVersion(source, version);
        return row == null ? null : row.status();
    }

    /** 当前已发布版本的状态；没有当前版时返回候选版状态。 */
    String currentStatus(String source) {
        VersionRow row = findCurrentVersion(source);
        if (row == null) {
            row = findCandidateVersion(source);
        }
        if (row != null) {
            return row.status();
        }
        return legacyCurrentStatus(source);
    }

    /** 当前已发布版本的文件哈希；处理中的版本不能让重试请求误判为成功去重。 */
    String findFileHash(String source) {
        VersionRow row = findCurrentVersion(source);
        if (row != null) {
            return row.fileHash();
        }
        return null;
    }

    /** 预留一个唯一版本号并登记处理批次，数据库行更新保证并发 source 不会拿到相同版本。 */
    int reserveVersion(String source, String batch, String fileHash, String visibility,
                       String status, String username) {
        ensureDocument(source);
        Integer version = jdbcTemplate.queryForObject(
                "UPDATE document SET next_version = next_version + 1, updated_at = now() "
                        + "WHERE source = ? RETURNING next_version - 1", Integer.class, source);
        if (version == null || version < 1) {
            throw new IllegalStateException("无法预留文档版本");
        }
        jdbcTemplate.update("INSERT INTO document_version(source, version, batch, file_hash, visibility, status, "
                        + "lifecycle_state, username) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                source, version, batch, fileHash, visibility, status, PROCESSING_STATE, username);
        return version;
    }

    /** 兼容旧调用方：返回下一版本，但新入库应使用 reserveVersion。 */
    int nextVersion(String source) {
        ensureDocument(source);
        Integer next = jdbcTemplate.queryForObject("SELECT next_version FROM document WHERE source = ?",
                Integer.class, source);
        return next == null ? 1 : next;
    }

    /** 当前已发布版本；没有时返回 0。 */
    int currentVersion(String source) {
        VersionRow row = findCurrentVersion(source);
        if (row != null) {
            return row.version();
        }
        row = findCandidateVersion(source);
        if (row != null) {
            return row.version();
        }
        return 0;
    }

    /** 当前已发布版本号，没有当前版时返回候选版本号。 */
    private int publishedVersion(String source) {
        VersionRow row = findCurrentVersion(source);
        return row == null ? 0 : row.version();
    }

    List<Integer> listVersions(String source) {
        List<Integer> versions = jdbcTemplate.query(
                "SELECT version FROM document_version WHERE source = ? AND lifecycle_state <> 'FAILED' "
                        + "ORDER BY version", (rs, rowNum) -> rs.getInt(1), source);
        if (!versions.isEmpty()) {
            return versions;
        }
        return jdbcTemplate.query(
                "SELECT DISTINCT COALESCE(CAST(NULLIF(metadata->>'version','') AS INTEGER), 1) "
                        + "FROM " + TABLE + " WHERE metadata->>'source' = ? ORDER BY 1",
                (rs, rowNum) -> rs.getInt(1), source);
    }

    /** 将目标候选版批准/驳回；没有候选版时才操作当前版。 */
    int setStatus(String source, String status) {
        VersionRow target = findCandidateVersion(source);
        if (target == null) {
            target = findCurrentVersion(source);
        }
        if (target == null) {
            return 0;
        }
        return reviewVersion(source, target.version(), status);
    }

    /** 回滚到历史版本：状态、current_version 和块元数据在一个事务中变更。 */
    @Transactional
    int rollback(String source, int version) {
        if (version < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 必须大于 0");
        }
        ensureDocument(source);
        Integer current = jdbcTemplate.queryForObject(
                "SELECT current_version FROM document WHERE source = ? FOR UPDATE", Integer.class, source);
        if (current == null || current < 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        if (version == current) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能回滚到当前版本");
        }
        VersionRow target = findVersion(source, version);
        if (target == null || SUPERSEDED_STATE.equals(target.lifecycleState())) {
            if (target == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标版本不存在");
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标版本不是历史版本");
        }
        VersionRow currentRow = findVersion(source, current);
        if (currentRow == null) {
            currentRow = new VersionRow(source, current, null, null, null, "approved", null,
                    ACTIVE_STATE, 0);
        }
        jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), "
                        + "'{status}', to_jsonb('superseded'::text)), '{prev_status}', "
                        + "to_jsonb(COALESCE(metadata->>'status','approved'))) "
                        + "WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                source, current);
        jdbcTemplate.update("UPDATE document_version SET lifecycle_state = ?, status = 'superseded', "
                        + "superseded_at = now(), updated_at = now() WHERE source = ? AND version = ?",
                SUPERSEDED_STATE, source, current);
        String restoredStatus = target.prevStatus() == null ? "approved" : target.prevStatus();
        jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), "
                        + "'{status}', to_jsonb(?::text)) WHERE metadata->>'source' = ? "
                        + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?", restoredStatus, source, version);
        int restored = jdbcTemplate.update("UPDATE document_version SET lifecycle_state = ?, status = ?, "
                        + "updated_at = now(), published_at = now() WHERE source = ? AND version = ?",
                ACTIVE_STATE, restoredStatus, source, version);
        jdbcTemplate.update("UPDATE document SET current_version = ?, updated_at = now() WHERE source = ?",
                version, source);
        return restored;
    }

    int countPending(String department) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM document_version WHERE status = 'pending' "
                + "AND lifecycle_state IN ('PENDING','PROCESSING')");
        List<Object> params = new ArrayList<>();
        appendVersionVisibility(sql, params, department);
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    int countBySource(String source) {
        VersionRow row = findCurrentVersion(source);
        if (row == null) {
            row = findCandidateVersion(source);
        }
        if (row != null) {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE
                            + " WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                    Integer.class, source, row.version());
            return count == null ? 0 : count;
        }
        return 0;
    }

    int countAllBySource(String source) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'source' = ?", Long.class, source);
        return count == null ? 0 : count.intValue();
    }

    DeleteDocumentResponse deleteDocument(String source) {
        int before = countAllBySource(source);
        removeBySource(source);
        jdbcTemplate.update("DELETE FROM document_version WHERE source = ?", source);
        jdbcTemplate.update("DELETE FROM document WHERE source = ?", source);
        int deletedChunks = before - countAllBySource(source);
        DeleteDocumentResponse response = new DeleteDocumentResponse();
        response.setSource(source);
        response.setDeletedChunks(deletedChunks);
        response.setDeleted(deletedChunks > 0);
        return response;
    }

    String previewText(String source, Integer version) {
        int targetVersion = version == null ? publishedVersion(source) : version;
        StringBuilder sql = new StringBuilder("SELECT COALESCE(text, '') FROM " + TABLE
                + " WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?");
        List<Object> params = new ArrayList<>();
        params.add(source);
        params.add(targetVersion);
        sql.append(" ORDER BY CAST(NULLIF(metadata->>'chunk_index','') AS INTEGER)");
        List<String> parts = jdbcTemplate.queryForList(sql.toString(), String.class, params.toArray());
        return String.join("\n\n", parts);
    }

    void removeBySource(String source) {
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(MetadataKeys.SOURCE).isEqualTo(source));
    }

    /** 按批次精确清理失败入库，不能误删同名文档其它版本。 */
    void removeByBatch(String batch) {
        if (batch == null || batch.isBlank()) {
            return;
        }
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(MetadataKeys.BATCH).isEqualTo(batch));
        jdbcTemplate.update("UPDATE document_version SET lifecycle_state = 'FAILED', updated_at = now() "
                        + "WHERE batch = ? AND lifecycle_state IN ('PROCESSING','PENDING')", batch);
    }

    /** 兼容旧的批次发布调用；新版本同时更新关系表。 */
    @Transactional
    void publishVersion(String source, String batch, String status) {
        VersionRow target = findVersionByBatch(source, batch);
        if (target == null && !relationRowsExist(source)) {
            int updated = jdbcTemplate.update("UPDATE " + TABLE
                    + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                    + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') = ?",
                    status, source, batch);
            jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), "
                    + "'{status}', to_jsonb('superseded'::text)), '{prev_status}', "
                    + "to_jsonb(COALESCE(metadata->>'status','approved'))) WHERE metadata->>'source' = ? "
                    + "AND COALESCE(metadata->>'batch','') <> ?", source, batch);
            return;
        }
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入库批次不存在");
        }
        int updated = jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') = ?",
                status, source, batch);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入库批次没有可发布的块");
        }
        ensureDocument(source);
        jdbcTemplate.queryForObject("SELECT current_version FROM document WHERE source = ? FOR UPDATE",
                Integer.class, source);
        Long chunkCount = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE
                        + " WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch','') = ?",
                Long.class, source, batch);
        if (chunkCount == null || chunkCount < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入库批次块数无效");
        }
        jdbcTemplate.update("UPDATE document_version SET chunk_count = ?, updated_at = now() "
                        + "WHERE source = ? AND version = ?", chunkCount.intValue(), source, target.version());
        if ("pending".equals(status)) {
            jdbcTemplate.update("UPDATE document_version SET status = ?, lifecycle_state = ?, updated_at = now() "
                            + "WHERE source = ? AND version = ?", status, PENDING_STATE, source, target.version());
        } else {
            jdbcTemplate.update("UPDATE document_version SET status = ?, lifecycle_state = ?, published_at = now(), "
                            + "updated_at = now() WHERE source = ? AND version = ?",
                    status, ACTIVE_STATE, source, target.version());
            markOtherVersionsSuperseded(source, target.version());
            jdbcTemplate.update("UPDATE document SET current_version = ?, updated_at = now() WHERE source = ?",
                    target.version(), source);
        }
    }

    int markSuperseded(String source, String keepBatch) {
        int updated = jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', "
                + "to_jsonb('superseded'::text)), '{prev_status}', "
                + "to_jsonb(COALESCE(metadata->>'status','approved'))) "
                + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') <> ?", source, keepBatch);
        if (relationRowsExist(source)) {
            jdbcTemplate.update("UPDATE document_version SET lifecycle_state = 'SUPERSEDED', status = 'superseded', "
                            + "updated_at = now() WHERE source = ? AND batch <> ? AND lifecycle_state <> 'FAILED'",
                    source, keepBatch);
        }
        return updated;
    }

    void attachTagsAndCategory(String source, int version, String category, String tags) {
        String effectiveCategory = DocumentManageService.blankToNull(category);
        String effectiveTags = DocumentManageService.blankToNull(tags);
        if (effectiveCategory == null && effectiveTags == null) {
            return;
        }
        List<Object> params = new ArrayList<>();
        String metadataExpr = "COALESCE(metadata, '{}'::jsonb)";
        if (effectiveCategory != null) {
            metadataExpr = "jsonb_set(" + metadataExpr + ", '{category}', to_jsonb(?::text))";
            params.add(effectiveCategory);
        }
        if (effectiveTags != null) {
            metadataExpr = "jsonb_set(" + metadataExpr + ", '{tags}', to_jsonb(?::text))";
            params.add(effectiveTags);
        }
        params.add(source);
        params.add(version);
        jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = " + metadataExpr
                + " WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                params.toArray());
    }

    int updateVisibility(String source, String visibility) {
        VersionRow target = findCandidateVersion(source);
        if (target == null) {
            target = findCurrentVersion(source);
        }
        if (target == null) {
            return 0;
        }
        jdbcTemplate.update("UPDATE document_version SET visibility = ?, updated_at = now() "
                        + "WHERE source = ? AND version = ?", visibility, source, target.version());
        return jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = "
                + "jsonb_set(COALESCE(metadata, '{}'::jsonb), '{visibility}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                visibility, source, target.version());
    }

    int purgeOldSuperseded(int keep) {
        List<VersionRef> targets = jdbcTemplate.query(
                "SELECT source, version FROM (SELECT source, version, row_number() OVER "
                        + "(PARTITION BY source ORDER BY version DESC) AS rn FROM document_version "
                        + "WHERE lifecycle_state = 'SUPERSEDED') ranked WHERE rn > ?",
                (rs, rowNum) -> new VersionRef(rs.getString("source"), rs.getInt("version")), keep);
        if (targets.isEmpty() && !relationRowsExist("")) {
            return jdbcTemplate.update("DELETE FROM " + TABLE + " c WHERE COALESCE(c.metadata->>'status','approved') = 'superseded' "
                    + "AND CAST(NULLIF(c.metadata->>'version','') AS INTEGER) <= "
                    + "(SELECT COALESCE(max(CAST(NULLIF(metadata->>'version','') AS INTEGER)), 0) - ? "
                    + "FROM " + TABLE + " WHERE metadata->>'source' = c.metadata->>'source')", keep);
        }
        int deleted = 0;
        for (VersionRef target : targets) {
            deleted += jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE metadata->>'source' = ? "
                    + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?", target.source(), target.version());
            jdbcTemplate.update("DELETE FROM document_version WHERE source = ? AND version = ?",
                    target.source(), target.version());
        }
        return deleted;
    }

    private int reviewVersion(String source, int version, String status) {
        int chunks = jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), "
                + "'{status}', to_jsonb(?::text)) WHERE metadata->>'source' = ? "
                + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?", status, source, version);
        if (chunks == 0) {
            return 0;
        }
        ensureDocument(source);
        jdbcTemplate.queryForObject("SELECT current_version FROM document WHERE source = ? FOR UPDATE",
                Integer.class, source);
        if ("approved".equals(status) || "rejected".equals(status)) {
            jdbcTemplate.update("UPDATE document_version SET status = ?, lifecycle_state = 'CURRENT', "
                            + "published_at = now(), updated_at = now() WHERE source = ? AND version = ?",
                    status, source, version);
            markOtherVersionsSuperseded(source, version);
            ensureDocument(source);
            jdbcTemplate.update("UPDATE document SET current_version = ?, updated_at = now() WHERE source = ?",
                    version, source);
        } else {
            jdbcTemplate.update("UPDATE document_version SET status = ?, lifecycle_state = 'PENDING', updated_at = now() "
                            + "WHERE source = ? AND version = ?", status, source, version);
        }
        return chunks;
    }

    private void markOtherVersionsSuperseded(String source, int keepVersion) {
        jdbcTemplate.update("UPDATE " + TABLE + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), "
                + "'{status}', to_jsonb('superseded'::text)), '{prev_status}', "
                + "to_jsonb(COALESCE(metadata->>'status','approved'))) WHERE metadata->>'source' = ? "
                + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) <> ? "
                + "AND COALESCE(metadata->>'status','approved') <> 'superseded'", source, keepVersion);
        jdbcTemplate.update("UPDATE document_version SET lifecycle_state = 'SUPERSEDED', status = 'superseded', "
                        + "updated_at = now() WHERE source = ? AND version <> ? AND lifecycle_state <> 'FAILED'",
                source, keepVersion);
    }

    private void appendVersionVisibility(StringBuilder sql, List<Object> params, String department) {
        if (department == null) {
            sql.append(" AND visibility = ?");
            params.add(Visibility.ALL);
        } else {
            sql.append(" AND visibility IN (?, ?)");
            params.add(Visibility.ALL);
            params.add(department);
        }
    }

    private void ensureDocument(String source) {
        jdbcTemplate.update("INSERT INTO document(source, next_version) VALUES (?, 1) ON CONFLICT (source) DO NOTHING",
                source);
    }

    private VersionRow findCurrentVersion(String source) {
        List<VersionRow> rows = queryVersions("SELECT source, version, batch, file_hash, visibility, status, "
                + "prev_status, lifecycle_state, chunk_count FROM document_version "
                + "WHERE source = ? AND lifecycle_state = 'CURRENT' ORDER BY version DESC LIMIT 1", source);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        VersionRow legacy = findLegacyVersion(source, false);
        if (legacy != null) {
            return legacy;
        }
        Integer version = legacyVersionNumber(source, false);
        return version == null || version < 1
                ? null
                : new VersionRow(source, version, null, null, null, "approved", null, ACTIVE_STATE, 0);
    }

    private VersionRow findCandidateVersion(String source) {
        List<VersionRow> rows = queryVersions("SELECT source, version, batch, file_hash, visibility, status, "
                + "prev_status, lifecycle_state, chunk_count FROM document_version "
                + "WHERE source = ? AND lifecycle_state IN ('PROCESSING','PENDING') ORDER BY version DESC LIMIT 1", source);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return findLegacyVersion(source, true);
    }

    private VersionRow findLegacyVersion(String source, boolean candidate) {
        String state = candidate ? "pending" : "approved";
        List<VersionRow> rows = jdbcTemplate.query("SELECT metadata->>'source' AS source, "
                        + "COALESCE(CAST(NULLIF(metadata->>'version','') AS INTEGER), 1) AS version, "
                        + "metadata->>'batch' AS batch, metadata->>'file_hash' AS file_hash, "
                        + "metadata->>'visibility' AS visibility, COALESCE(metadata->>'status','approved') AS status, "
                        + "metadata->>'prev_status' AS prev_status, metadata->>'chunk_index' AS chunk_count "
                        + "FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND COALESCE(metadata->>'status','approved') = ? "
                        + "ORDER BY CAST(NULLIF(metadata->>'version','') AS INTEGER) DESC LIMIT 1",
                (rs, rowNum) -> new VersionRow(rs.getString("source"), rs.getInt("version"),
                        rs.getString("batch"), rs.getString("file_hash"), rs.getString("visibility"),
                        rs.getString("status"), rs.getString("prev_status"),
                        candidate ? PENDING_STATE : ACTIVE_STATE, 0), source, state);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return null;
    }

    private Integer legacyVersionNumber(String source, boolean pending) {
        String status = pending ? "pending" : "approved";
        return jdbcTemplate.queryForObject("SELECT max(CAST(NULLIF(metadata->>'version','') AS INTEGER)) FROM "
                        + TABLE + " WHERE metadata->>'source' = ? AND COALESCE(metadata->>'status','approved') = ?",
                Integer.class, source, status);
    }

    private VersionRow findVersion(String source, int version) {
        List<VersionRow> rows = queryVersions("SELECT source, version, batch, file_hash, visibility, status, "
                + "prev_status, lifecycle_state, chunk_count FROM document_version WHERE source = ? AND version = ?",
                source, version);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<VersionRow> legacy = jdbcTemplate.query("SELECT metadata->>'source' AS source, "
                        + "COALESCE(CAST(NULLIF(metadata->>'version','') AS INTEGER), 1) AS version, "
                        + "metadata->>'batch' AS batch, metadata->>'file_hash' AS file_hash, "
                        + "metadata->>'visibility' AS visibility, COALESCE(metadata->>'status','approved') AS status, "
                        + "metadata->>'prev_status' AS prev_status, 0 AS chunk_count FROM " + TABLE
                        + " WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ? LIMIT 1",
                (rs, rowNum) -> new VersionRow(rs.getString("source"), rs.getInt("version"),
                        rs.getString("batch"), rs.getString("file_hash"), rs.getString("visibility"),
                        rs.getString("status"), rs.getString("prev_status"),
                        "superseded".equals(rs.getString("status")) ? SUPERSEDED_STATE : ACTIVE_STATE, 0),
                source, version);
        if (!legacy.isEmpty()) {
            return legacy.get(0);
        }
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE
                        + " WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                Integer.class, source, version);
        return count != null && count > 0
                ? new VersionRow(source, version, null, null, null, "superseded", "approved", SUPERSEDED_STATE, 0)
                : null;
    }

    private VersionRow findVersionByBatch(String source, String batch) {
        List<VersionRow> rows = queryVersions("SELECT source, version, batch, file_hash, visibility, status, "
                + "prev_status, lifecycle_state, chunk_count FROM document_version WHERE source = ? AND batch = ?",
                source, batch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean relationRowsExist(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_version WHERE source = ?", Integer.class, source);
        return count != null && count > 0;
    }

    private List<VersionRow> queryVersions(String sql, Object... params) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new VersionRow(rs.getString("source"), rs.getInt("version"),
                rs.getString("batch"), rs.getString("file_hash"), rs.getString("visibility"),
                rs.getString("status"), rs.getString("prev_status"), rs.getString("lifecycle_state"),
                rs.getInt("chunk_count")), params);
    }

    private String legacyCurrentStatus(String source) {
        List<String> statuses = jdbcTemplate.query("SELECT metadata->>'status' FROM " + TABLE
                        + " WHERE metadata->>'source' = ? AND COALESCE(metadata->>'status','approved') <> 'superseded' "
                        + "ORDER BY CAST(NULLIF(metadata->>'version','') AS INTEGER) DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source);
        return statuses.isEmpty() || statuses.get(0) == null ? null : statuses.get(0);
    }

    private record VersionRow(String source, int version, String batch, String fileHash, String visibility,
                              String status, String prevStatus, String lifecycleState, int chunkCount) {
    }

    private record VersionRef(String source, int version) {
    }
}
