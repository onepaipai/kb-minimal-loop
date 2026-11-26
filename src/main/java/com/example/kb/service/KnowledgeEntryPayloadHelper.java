package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.kb.common.Visibility;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

final class KnowledgeEntryPayloadHelper {

    /** 条目入库的虚拟文件名后缀：走 .md 链路（UTF-8 直读，保留标题结构） */
    static final String SOURCE_SUFFIX = ".md";

    private final JdbcTemplate jdbcTemplate;
    private final IngestService ingestService;
    private final DocumentManageService documentManageService;
    private final DepartmentService departmentService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    KnowledgeEntryPayloadHelper(JdbcTemplate jdbcTemplate, IngestService ingestService,
                                DocumentManageService documentManageService, DepartmentService departmentService,
                                NotificationService notificationService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ingestService = ingestService;
        this.documentManageService = documentManageService;
        this.departmentService = departmentService;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    /** 入库前的公共校验：上传权限、可见性规则、部门登记，与文件上传完全一致。 */
    String prepareVisibility(UserPrincipal user, String visibility) {
        String effectiveVisibility = Visibility.ALL;
        String requested = DocumentManageService.blankToNull(visibility);
        if (requested != null) {
            effectiveVisibility = Visibility.normalize(requested);
        }
        DepartmentScope.requireCanUpload(user);
        DepartmentScope.checkUploadVisibility(user, effectiveVisibility);
        departmentService.ensureDepartment(effectiveVisibility);
        return effectiveVisibility;
    }

    /** 复用 IngestService 的入库管线：切块、向量化、先入后发布、同名替换、哈希去重都在里面。 */
    KnowledgeEntryService.KnowledgeEntrySaved ingestChunks(UUID id, String title, String source, String content,
                                                            String visibility, String status, String category,
                                                            String tags) {
        try {
            // 在线条目没有上传文件，不存原件；.md 后缀走 UTF-8 直读，不走 Tika
            ingestService.ingestBytes(content.getBytes(StandardCharsets.UTF_8), source, visibility, status, false);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "知识条目入库失败");
        }
        int version = documentManageService.currentVersion(source);
        documentManageService.attachTagsAndCategory(source, version, category, tags);
        int chunks = documentManageService.countBySource(source);
        return new KnowledgeEntryService.KnowledgeEntrySaved(id, title, source, chunks, visibility, status);
    }

    /** 待审批的条目给管理员与对应部门管理员发提醒（与文件上传同一套通知）。 */
    void notifyIfPending(String source, String status) {
        if ("pending".equals(status)) {
            notificationService.notifyPendingApproval(source, documentManageService.getVisibility(source));
        }
    }

    /** 条目来源不能与现有文档撞名：撞名会让同名替换管线把别人的文档当旧版替换掉。 */
    void requireSourceFree(String source) {
        if (documentManageService.getVisibility(source) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在同名文档，请换个标题");
        }
    }

    EntryRow requireEntry(UUID id) {
        List<EntryRow> rows = jdbcTemplate.query(
                "SELECT id, title, source, content, category, tags, visibility, status, username, "
                        + "created_at, updated_at FROM knowledge_entry WHERE id = ?",
                (rs, rowNum) -> mapRow(rs), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识条目不存在");
        }
        return rows.get(0);
    }

    private static EntryRow mapRow(ResultSet rs) throws SQLException {
        return new EntryRow(UUID.fromString(rs.getString("id")), rs.getString("title"), rs.getString("source"),
                rs.getString("content"), rs.getString("category"), rs.getString("tags"),
                rs.getString("visibility"), rs.getString("status"), rs.getString("username"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    /** 标签归一：按中英文逗号/分号拆开、去空格、忽略空段，重新用英文逗号连接；全空返回 null。 */
    static String normalizeTags(String tags) {
        String cleaned = DocumentManageService.blankToNull(tags);
        if (cleaned == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String tag : cleaned.split("[,，;；]")) {
            String trimmed = tag.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(trimmed);
        }
        if (builder.length() == 0) {
            return null;
        }
        return builder.toString();
    }

    /** 前者非空白用前者，否则用后者（更新时 null 字段沿用原值）。 */
    static String firstNonBlank(String value, String fallback) {
        String cleaned = DocumentManageService.blankToNull(value);
        if (cleaned != null) {
            return cleaned;
        }
        return fallback;
    }
}

/** 内部行：全字段，供更新、详情与权限判断。 */
record EntryRow(UUID id, String title, String source, String content, String category, String tags,
                String visibility, String status, String username,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
