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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
        // 已 superseded 的旧版块不进清单（块数与版本号都只算当前版）；状态筛子本身也碰不到它
        StringBuilder where = new StringBuilder("metadata->>'source' IS NOT NULL AND " + visibilityCondition
                + " AND COALESCE(metadata->>'status','approved') <> 'superseded'");
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

    /** 某篇文档当前版本的文件哈希（去重用）：只看未被 superseded 的现行版，没有块或老块没存哈希时返回 null。 */
    public String findFileHash(String source) {
        List<String> values = jdbcTemplate.query(
                "SELECT metadata->>'file_hash' FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND COALESCE(metadata->>'status','approved') <> 'superseded' LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 同名替换的下一个版本号：已有最大版本 +1，首版为 1。 */
    public int nextVersion(String source) {
        return currentVersion(source) + 1;
    }

    /** 某篇文档的当前版本号（最大版，含已 superseded 的旧版）；不存在返回 0。 */
    public int currentVersion(String source) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(CAST(NULLIF(metadata->>'version','') AS INTEGER)), 0) FROM " + TABLE
                        + " WHERE metadata->>'source' = ?",
                Integer.class, source);
        return max == null ? 0 : max;
    }

    /** 某篇文档的全部版本号（从小到大，含已 superseded 的旧版），回滚下拉框用；不存在返回空列表。 */
    public List<Integer> listVersions(String source) {
        return jdbcTemplate.query(
                "SELECT DISTINCT CAST(NULLIF(metadata->>'version','') AS INTEGER) AS version FROM " + TABLE
                        + " WHERE metadata->>'source' = ? ORDER BY version",
                (rs, rowNum) -> rs.getInt("version"), source);
    }

    /** 审批：当前版本的块统一改状态，返回改动的块数（0 表示文档不存在）；已 superseded 的旧版不动。 */
    public int setStatus(String source, String status) {
        int version = currentVersion(source);
        if (version == 0) {
            return 0;
        }
        return jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                status, source, version);
    }

    /**
     * 回滚到指定版本：目标版恢复成它被替换之前的状态（prev_status，缺失按 approved 算），当前版改成 superseded。
     * 目标版不存在或就是当前版时抛错；返回恢复的块数。
     */
    public int rollback(String source, int version) {
        int current = currentVersion(source);
        if (current == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        if (version >= current) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能回滚到比当前版更早的版本");
        }
        Integer targetChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                Integer.class, source, version);
        if (targetChunks == null || targetChunks == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标版本不存在");
        }
        markSupersededVersion(source, current);
        return jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', "
                + "to_jsonb(COALESCE(metadata->>'prev_status','approved'))) "
                + "WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                source, version);
    }

    /** 把指定版本的块改成 superseded，同时把改之前的状态记到 prev_status（回滚时按它恢复）。 */
    private int markSupersededVersion(String source, int version) {
        return jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', "
                + "to_jsonb('superseded'::text)), '{prev_status}', "
                + "to_jsonb(COALESCE(metadata->>'status','approved'))) "
                + "WHERE metadata->>'source' = ? AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?",
                source, version);
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
     * 统计指定 source 的当前版块数（已 superseded 的旧版不算）。Postgres 的 count(*) 返回 bigint，用 Long 接收避免类型窄化问题。
     */
    public int countBySource(String source) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND COALESCE(metadata->>'status','approved') <> 'superseded'",
                Long.class, source);
        if (count == null) {
            return 0;
        }
        return count.intValue();
    }

    /** 统计指定 source 的全部块数（含已 superseded 的旧版），删除接口的删除量用。 */
    private int countAllBySource(String source) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'source' = ?",
                Long.class, source);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 删除指定文档的全部块（含已 superseded 的旧版），deletedChunks 为删除前后统计差值（实际删除量）。
     */
    public DeleteDocumentResponse deleteDocument(String source) {
        int before = countAllBySource(source);
        removeBySource(source);
        int deletedChunks = before - countAllBySource(source);
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
     * 同名替换的第二步：旧版块不删，改成 superseded（问答与清单自动排除），同时把改之前的状态记到 prev_status，
     * 回滚时按它恢复（没批过的旧版回滚后还是待审批，不会悄悄发布）。
     * 用 SQL 而不用 Filter：旧块可能没有 batch 字段，Filter 的不等于匹配不到 NULL，COALESCE 才能兜住。
     */
    public int markSuperseded(String source, String keepBatch) {
        return jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', "
                + "to_jsonb('superseded'::text)), '{prev_status}', "
                + "to_jsonb(COALESCE(metadata->>'status','approved'))) "
                + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') <> ?", source, keepBatch);
    }

    /**
     * 同名替换的发布一步：新版块先以 pending 入库（问答查不到），这里一个事务里把它改成目标状态，
     * 同时把其余旧版标成 superseded。入库中途断了（向量接口报错、断库）发布没跑，旧版还活着、新版不可见，
     * 问答不会同时看到新旧两版；发布本身是原子的，断在中间会整体回滚。
     */
    @Transactional
    public void publishVersion(String source, String batch, String status) {
        jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') = ?",
                status, source, batch);
        markSuperseded(source, batch);
    }

    /**
     * 清理旧版：每篇文档只保留最新 keep 个 superseded 旧版，更早的彻底删除（回滚只认保留的版本）。
     * 只删 superseded 的块，当前版（pending/approved/rejected）永远不动；返回删除的块数。
     */
    public int purgeOldSuperseded(int keep) {
        return jdbcTemplate.update("DELETE FROM " + TABLE + " c "
                + "WHERE COALESCE(c.metadata->>'status', 'approved') = 'superseded' "
                + "AND CAST(NULLIF(c.metadata->>'version', '') AS INTEGER) <= "
                + "(SELECT COALESCE(max(CAST(NULLIF(metadata->>'version', '') AS INTEGER)), 0) - ? "
                + "FROM " + TABLE + " WHERE metadata->>'source' = c.metadata->>'source')", keep);
    }
}
