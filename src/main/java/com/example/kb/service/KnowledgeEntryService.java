package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.example.kb.common.Visibility;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识条目：在线撰写正文直接入库，不必做成文件上传。
 * 正文原文存 knowledge_entry 表供编辑；切块向量化复用 IngestService 的同名替换管线（source = 标题.md），
 * 审批状态与版本号规则和文件上传完全一致；分类与标签在入库成功后回填到当前版块的元数据上。
 */
@Service
public class KnowledgeEntryService {

    /** 条目清单上限：一次最多返回的条数（后台管理用，不需要真分页） */
    private static final int LIST_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final IngestService ingestService;
    private final DocumentManageService documentManageService;
    private final DepartmentService departmentService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final KnowledgeEntryPayloadHelper payloadHelper;

    public KnowledgeEntryService(JdbcTemplate jdbcTemplate, IngestService ingestService,
                                 DocumentManageService documentManageService, DepartmentService departmentService,
                                 NotificationService notificationService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ingestService = ingestService;
        this.documentManageService = documentManageService;
        this.departmentService = departmentService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.payloadHelper = new KnowledgeEntryPayloadHelper(jdbcTemplate, ingestService, documentManageService,
                departmentService, notificationService, auditService);
    }

    /** 条目清单条目：不含正文全文。 */
    public record KnowledgeEntryItem(UUID id, String title, String source, String category, String tags,
                                     String visibility, String status, String username,
                                     OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 条目详情：含正文全文（编辑回填用）。 */
    public record KnowledgeEntryDetail(UUID id, String title, String source, String content, String category,
                                       String tags, String visibility, String status, String username,
                                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** 条目入库结果：标题、来源（块元数据键）、块数、可见性与审批状态。 */
    public record KnowledgeEntrySaved(UUID id, String title, String source, int chunks,
                                      String visibility, String status) {
    }

    /** 新建条目并入库：标题即来源，同名（含与上传文档撞名）报 409；审批状态按角色与可见性定。 */
    public KnowledgeEntrySaved create(UserPrincipal user, String title, String content,
                                      String category, String tags, String visibility) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        String effectiveVisibility = payloadHelper.prepareVisibility(user, visibility);
        String source = title.trim() + KnowledgeEntryPayloadHelper.SOURCE_SUFFIX;
        payloadHelper.requireSourceFree(source);
        String status = DepartmentScope.initialDocStatus(user, effectiveVisibility);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO knowledge_entry(id, title, source, content, category, tags, "
                        + "visibility, status, username) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, title.trim(), source, content, DocumentManageService.blankToNull(category),
                normalizeTags(tags), effectiveVisibility, status, user.username());
        KnowledgeEntrySaved saved = payloadHelper.ingestChunks(id, title.trim(), source, content, effectiveVisibility,
                status, category, tags);
        payloadHelper.notifyIfPending(source, status);
        auditService.record(user.username(), "CREATE_ENTRY", title.trim(),
                "visibility=" + effectiveVisibility + " status=" + status);
        return saved;
    }

    /** 更新条目并重新入库：正文/分类/标签/可见性为 null 的沿用原值；标题不可改；重新入库走同名替换。 */
    public KnowledgeEntrySaved update(UUID id, UserPrincipal user, String content,
                                      String category, String tags, String visibility) {
        EntryRow row = payloadHelper.requireEntry(id);
        DepartmentScope.requireCanDeleteDocument(user, row.visibility());
        String effectiveVisibility = row.visibility();
        String requestedVisibility = DocumentManageService.blankToNull(visibility);
        if (requestedVisibility != null) {
            effectiveVisibility = Visibility.normalize(requestedVisibility);
            DepartmentScope.checkUploadVisibility(user, effectiveVisibility);
            departmentService.ensureDepartment(effectiveVisibility);
        }
        String effectiveContent = payloadHelper.firstNonBlank(content, row.content());
        String effectiveCategory = payloadHelper.firstNonBlank(category, row.category());
        String effectiveTags = payloadHelper.firstNonBlank(tags, row.tags());
        String status = DepartmentScope.initialDocStatus(user, effectiveVisibility);
        jdbcTemplate.update("UPDATE knowledge_entry SET content = ?, category = ?, tags = ?, visibility = ?, "
                + "status = ?, updated_at = now() WHERE id = ?", effectiveContent, effectiveCategory, effectiveTags,
                effectiveVisibility, status, id);
        KnowledgeEntrySaved saved = payloadHelper.ingestChunks(id, row.title(), row.source(), effectiveContent,
                effectiveVisibility, status, effectiveCategory, effectiveTags);
        auditService.record(user.username(), "UPDATE_ENTRY", row.title(),
                "visibility=" + effectiveVisibility + " status=" + status);
        return saved;
    }

    /** 删除条目：块（含旧版）与条目行一起删；权限与文档删除一致。 */
    public void delete(UUID id, UserPrincipal user) {
        EntryRow row = payloadHelper.requireEntry(id);
        DepartmentScope.requireCanDeleteDocument(user, row.visibility());
        documentManageService.removeBySource(row.source());
        jdbcTemplate.update("DELETE FROM knowledge_entry WHERE id = ?", id);
        auditService.record(user.username(), "DELETE_ENTRY", row.title(), null);
    }

    /** 该来源名是否已被知识条目占用（文件上传撞名校验用）。 */
    public boolean existsBySource(String source) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_entry WHERE source = ?", Integer.class, source);
        return count != null && count > 0;
    }

    /** 文档接口批准/驳回后同步条目行状态；来源不是条目时静默跳过（UPDATE 影响 0 行）。 */
    public void syncStatusBySource(String source, String status) {
        if (source == null || status == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE knowledge_entry SET status = ?, updated_at = now() WHERE source = ?", status, source);
    }

    /** 文档接口删除后清理条目行（块已由文档删除删掉）；来源不是条目时静默跳过。 */
    public void deleteBySource(String source) {
        if (source == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM knowledge_entry WHERE source = ?", source);
    }

    /**
     * 条目清单：department 为 null 只列全员可见的，传了列全员加该部门的。
     * 管理视角（includePending=true）看全部状态；普通视角只看已发布的，外加本人写的
     * （编辑写的条目待审批时自己还能看到、能改，不至于在清单里消失）。
     */
    public List<KnowledgeEntryItem> list(String department, String status, boolean includePending,
                                         String callerUsername) {
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (department == null) {
            where.append("visibility = ?");
            params.add(Visibility.ALL);
        } else {
            where.append("visibility IN (?, ?)");
            params.add(Visibility.ALL);
            params.add(department);
        }
        String cleanedStatus = DocumentManageService.blankToNull(status);
        if (cleanedStatus != null) {
            if (callerUsername != null) {
                where.append(" AND (status = ? OR username = ?)");
                params.add(cleanedStatus.toLowerCase(Locale.ROOT));
                params.add(callerUsername);
            } else {
                where.append(" AND status = ?");
                params.add(cleanedStatus.toLowerCase(Locale.ROOT));
            }
        } else if (includePending) {
            // 管理视角：全部状态都列
        } else if (callerUsername != null) {
            where.append(" AND (status = 'approved' OR username = ?)");
            params.add(callerUsername);
        } else {
            where.append(" AND status = 'approved'");
        }
        params.add(LIST_LIMIT);
        return jdbcTemplate.query("SELECT id, title, source, category, tags, visibility, status, username, "
                        + "created_at, updated_at FROM knowledge_entry WHERE " + where
                        + " ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> new KnowledgeEntryItem(UUID.fromString(rs.getString("id")), rs.getString("title"),
                        rs.getString("source"), rs.getString("category"), rs.getString("tags"),
                        rs.getString("visibility"), rs.getString("status"), rs.getString("username"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                params.toArray());
    }

    /** 条目详情（含正文）：管理员、本人、以及可见范围（all 或本部门）内的人可看，其余按不存在处理。 */
    public KnowledgeEntryDetail get(UUID id, UserPrincipal user) {
        EntryRow row = payloadHelper.requireEntry(id);
        boolean visibleToUser = user != null && (user.isAdmin() || user.username().equals(row.username())
                || Visibility.ALL.equals(row.visibility())
                || (user.department() != null && user.department().equals(row.visibility())));
        if (!visibleToUser) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识条目不存在");
        }
        return new KnowledgeEntryDetail(row.id(), row.title(), row.source(), row.content(), row.category(),
                row.tags(), row.visibility(), row.status(), row.username(), row.createdAt(), row.updatedAt());
    }

    /** 标签归一：按中英文逗号/分号拆开、去空格、忽略空段，重新用英文逗号连接；全空返回 null。 */
    static String normalizeTags(String tags) {
        return KnowledgeEntryPayloadHelper.normalizeTags(tags);
    }

}
