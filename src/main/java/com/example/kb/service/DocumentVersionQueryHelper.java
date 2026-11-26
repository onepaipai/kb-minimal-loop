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
import org.springframework.web.server.ResponseStatusException;

final class DocumentVersionQueryHelper {

    private static final String TABLE = "chunk_embedding";

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

    /** 某篇文档的可见性（审批与删除的权限判断用）；不存在返回 null。 */
    String getVisibility(String source) {
        List<String> values = jdbcTemplate.query(
                "SELECT metadata->>'visibility' FROM " + TABLE + " WHERE metadata->>'source' = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source);
        if (values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    /** 某篇文档当前版的审批状态（回滚后同步条目行状态用）；不存在返回 null，块没存状态按 approved 算。 */
    String currentStatus(String source) {
        int version = currentVersion(source);
        if (version == 0) {
            return null;
        }
        List<String> statuses = jdbcTemplate.query(
                "SELECT metadata->>'status' FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND CAST(NULLIF(metadata->>'version','') AS INTEGER) = ? LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source, version);
        if (statuses.isEmpty() || statuses.get(0) == null) {
            return "approved";
        }
        return statuses.get(0);
    }

    /** 某篇文档当前版本的文件哈希（去重用）：只看未被 superseded 的现行版，没有块或老块没存哈希时返回 null。 */
    String findFileHash(String source) {
        List<String> values = jdbcTemplate.query(
                "SELECT metadata->>'file_hash' FROM " + TABLE + " WHERE metadata->>'source' = ? "
                        + "AND COALESCE(metadata->>'status','approved') <> 'superseded' LIMIT 1",
                (rs, rowNum) -> rs.getString(1), source);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 同名替换的下一个版本号：已有最大版本 +1，首版为 1。 */
    int nextVersion(String source) {
        return currentVersion(source) + 1;
    }

    /** 某篇文档的当前版本号（最大版，含已 superseded 的旧版）；不存在返回 0。 */
    int currentVersion(String source) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(CAST(NULLIF(metadata->>'version','') AS INTEGER)), 0) FROM " + TABLE
                        + " WHERE metadata->>'source' = ?",
                Integer.class, source);
        return max == null ? 0 : max;
    }

    /** 某篇文档的全部版本号（从小到大，含已 superseded 的旧版），回滚下拉框用；不存在返回空列表。 */
    List<Integer> listVersions(String source) {
        return jdbcTemplate.query(
                "SELECT DISTINCT CAST(NULLIF(metadata->>'version','') AS INTEGER) AS version FROM " + TABLE
                        + " WHERE metadata->>'source' = ? ORDER BY version",
                (rs, rowNum) -> rs.getInt("version"), source);
    }

    /** 审批：当前版本的块统一改状态，返回改动的块数（0 表示文档不存在）；已 superseded 的旧版不动。 */
    int setStatus(String source, String status) {
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
    int rollback(String source, int version) {
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
    int countPending(String department) {
        String where = "COALESCE(metadata->>'status','approved') = 'pending' AND " + buildVisibilityCondition(department);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT metadata->>'source') FROM " + TABLE + " WHERE " + where,
                Integer.class, buildVisibilityParams(department));
        return count == null ? 0 : count;
    }

    /**
     * 统计指定 source 的当前版块数（已 superseded 的旧版不算）。Postgres 的 count(*) 返回 bigint，用 Long 接收避免类型窄化问题。
     */
    int countBySource(String source) {
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
    DeleteDocumentResponse deleteDocument(String source) {
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
    void removeBySource(String source) {
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(MetadataKeys.SOURCE).isEqualTo(source));
    }

    /**
     * 同名替换的第二步：旧版块不删，改成 superseded（问答与清单自动排除），同时把改之前的状态记到 prev_status，
     * 回滚时按它恢复（没批过的旧版回滚后还是待审批，不会悄悄发布）。
     * 用 SQL 而不用 Filter：旧块可能没有 batch 字段，Filter 的不等于匹配不到 NULL，COALESCE 才能兜住。
     */
    int markSuperseded(String source, String keepBatch) {
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
    void publishVersion(String source, String batch, String status) {
        jdbcTemplate.update("UPDATE " + TABLE
                + " SET metadata = jsonb_set(COALESCE(metadata, '{}'::jsonb), '{status}', to_jsonb(?::text)) "
                + "WHERE metadata->>'source' = ? AND COALESCE(metadata->>'batch', '') = ?",
                status, source, batch);
        markSuperseded(source, batch);
    }

    /**
     * 条目分类与标签回填：写到指定版块的元数据上（其余版本不带），文档清单按它们展示与筛。
     * category 与 tags 都为空时不做任何更新。
     */
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

    int purgeOldSuperseded(int keep) {
        return jdbcTemplate.update("DELETE FROM " + TABLE + " c "
                + "WHERE COALESCE(c.metadata->>'status', 'approved') = 'superseded' "
                + "AND CAST(NULLIF(c.metadata->>'version', '') AS INTEGER) <= "
                + "(SELECT COALESCE(max(CAST(NULLIF(metadata->>'version', '') AS INTEGER)), 0) - ? "
                + "FROM " + TABLE + " WHERE metadata->>'source' = c.metadata->>'source')", keep);
    }
}
