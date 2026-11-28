package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import com.example.kb.common.Visibility;
import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.dto.DocumentSummary;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 知识库文档管理服务：清单、版本、审批、回滚、批次补偿和原件生命周期。 */
@Service
public class DocumentManageService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentVersionQueryHelper documentVersionQueryHelper;
    private final DocumentBlobService documentBlobService;

    public DocumentManageService(JdbcTemplate jdbcTemplate, EmbeddingStore<TextSegment> embeddingStore,
                                 DocumentBlobService documentBlobService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingStore = embeddingStore;
        this.documentBlobService = documentBlobService;
        this.documentVersionQueryHelper = new DocumentVersionQueryHelper(jdbcTemplate, embeddingStore);
    }

    /** 按版本事实表列出每个 source 的当前版或待审批候选版。 */
    public DocumentListResponse listDocuments(String department, int page, int size,
                                              String status, boolean includePending) {
        StringBuilder visibility = new StringBuilder();
        List<Object> visibilityParams = new ArrayList<>();
        if (department == null) {
            visibility.append("dv.visibility = ?");
            visibilityParams.add(Visibility.ALL);
        } else {
            visibility.append("dv.visibility IN (?, ?)");
            visibilityParams.add(Visibility.ALL);
            visibilityParams.add(department);
        }
        StringBuilder selection = new StringBuilder("SELECT DISTINCT ON (dv.source) dv.source, dv.visibility, "
                + "dv.status, dv.version, dv.chunk_count, "
                + "EXISTS (SELECT 1 FROM document_blob b WHERE b.source = dv.source AND b.version = dv.version) AS has_blob "
                + "FROM document_version dv WHERE ").append(visibility);
        List<Object> params = new ArrayList<>(visibilityParams);
        selection.append(" AND dv.lifecycle_state <> 'FAILED'");
        if (status != null && !status.isBlank()) {
            selection.append(" AND dv.status = ?");
            params.add(status.trim().toLowerCase());
        } else if (!includePending) {
            selection.append(" AND dv.lifecycle_state = 'CURRENT' AND dv.status = 'approved'");
        } else {
            selection.append(" AND dv.lifecycle_state IN ('CURRENT','PENDING','PROCESSING')");
        }
        selection.append(" ORDER BY dv.source, CASE WHEN dv.lifecycle_state IN ('PENDING','PROCESSING') "
                + "THEN 0 ELSE 1 END, dv.version DESC");

        String countSql = "SELECT count(*) FROM (" + selection + ") selected";
        Integer total = jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());
        String sql = "SELECT source, visibility, status, version, chunk_count, has_blob FROM (" + selection
                + ") selected LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(page * size);
        List<DocumentSummary> documents = jdbcTemplate.query(sql, (rs, rowNum) -> {
            DocumentSummary summary = new DocumentSummary();
            summary.setSource(rs.getString("source"));
            summary.setVisibility(rs.getString("visibility"));
            summary.setChunks(rs.getInt("chunk_count"));
            summary.setStatus(rs.getString("status"));
            summary.setVersion(rs.getInt("version"));
            summary.setHasBlob(rs.getBoolean("has_blob"));
            return summary;
        }, pageParams.toArray());
        DocumentListResponse response = new DocumentListResponse();
        response.setDocuments(documents);
        response.setTotal(total == null ? 0 : total);
        response.setPage(page);
        response.setSize(size);
        return response;
    }

    public String getVisibility(String source) {
        return documentVersionQueryHelper.getVisibility(source);
    }

    public String getCandidateVisibility(String source) {
        return documentVersionQueryHelper.getCandidateVisibility(source);
    }

    public String getVersionVisibility(String source, int version) {
        return documentVersionQueryHelper.getVersionVisibility(source, version);
    }

    public String getVersionStatus(String source, int version) {
        return documentVersionQueryHelper.getVersionStatus(source, version);
    }

    public boolean hasDocument(String source) {
        return getVisibility(source) != null || documentVersionQueryHelper.getCandidateVisibility(source) != null;
    }

    /** 跨文件上传与在线条目的 source 占用保护，利用 document 主键完成原子抢占。 */
    public boolean reserveSource(String source) {
        return jdbcTemplate.update("INSERT INTO document(source, next_version) VALUES (?, 1) "
                + "ON CONFLICT (source) DO NOTHING", source) > 0;
    }

    public void releaseSourceIfEmpty(String source) {
        jdbcTemplate.update("DELETE FROM document WHERE source = ? AND current_version IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM document_version WHERE document_version.source = document.source)",
                source);
    }

    public String currentStatus(String source) {
        return documentVersionQueryHelper.currentStatus(source);
    }

    public String findFileHash(String source) {
        return documentVersionQueryHelper.findFileHash(source);
    }

    public int reserveVersion(String source, String batch, String fileHash, String visibility,
                              String status, String username) {
        return documentVersionQueryHelper.reserveVersion(source, batch, fileHash, visibility, status, username);
    }

    /** 兼容历史调用方；新入库使用 reserveVersion 避免并发版本冲突。 */
    public int nextVersion(String source) {
        return documentVersionQueryHelper.nextVersion(source);
    }

    public int currentVersion(String source) {
        return documentVersionQueryHelper.currentVersion(source);
    }

    public List<Integer> listVersions(String source) {
        return documentVersionQueryHelper.listVersions(source);
    }

    public int setStatus(String source, String status) {
        return documentVersionQueryHelper.setStatus(source, status);
    }

    @Transactional
    public int rollback(String source, int version) {
        return documentVersionQueryHelper.rollback(source, version);
    }

    public int countPending(String department) {
        return documentVersionQueryHelper.countPending(department);
    }

    public int countBySource(String source) {
        return documentVersionQueryHelper.countBySource(source);
    }

    public DeleteDocumentResponse deleteDocument(String source) {
        DeleteDocumentResponse response = documentVersionQueryHelper.deleteDocument(source);
        if (response.isDeleted()) {
            documentBlobService.deleteBySource(source);
        }
        return response;
    }

    public String previewText(String source) {
        return previewText(source, null);
    }

    public String previewText(String source, Integer version) {
        if (version != null && version < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 必须大于 0");
        }
        return documentVersionQueryHelper.previewText(source, version);
    }

    public void removeBySource(String source) {
        documentVersionQueryHelper.removeBySource(source);
        documentBlobService.deleteBySource(source);
        jdbcTemplate.update("DELETE FROM document_version WHERE source = ?", source);
        jdbcTemplate.update("DELETE FROM document WHERE source = ?", source);
    }

    public void removeByBatch(String batch, String source, int version) {
        documentVersionQueryHelper.removeByBatch(batch);
        documentBlobService.deleteVersion(source, version);
    }

    @Transactional
    public void publishVersion(String source, String batch, String status) {
        documentVersionQueryHelper.publishVersion(source, batch, status);
    }

    public int markSuperseded(String source, String keepBatch) {
        return documentVersionQueryHelper.markSuperseded(source, keepBatch);
    }

    public void attachTagsAndCategory(String source, int version, String category, String tags) {
        documentVersionQueryHelper.attachTagsAndCategory(source, version, category, tags);
    }

    public int updateVisibility(String source, String visibility) {
        return documentVersionQueryHelper.updateVisibility(source, visibility);
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    public int purgeOldSuperseded(int keep) {
        documentBlobService.purgeOld(keep);
        return documentVersionQueryHelper.purgeOldSuperseded(keep);
    }
}
