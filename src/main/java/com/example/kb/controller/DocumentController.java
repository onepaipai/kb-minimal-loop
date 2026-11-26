package com.example.kb.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.kb.common.Visibility;
import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentBatchDeleteRequest;
import com.example.kb.dto.DocumentBatchReviewRequest;
import com.example.kb.dto.DocumentBatchVisibilityRequest;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.service.AuditService;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.DocumentBlobService;
import com.example.kb.service.DocumentManageService;
import com.example.kb.service.KnowledgeEntryService;
import com.example.kb.service.NotificationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 已入库文档管理接口：清单、删除与审批（批准/驳回）；条目来源的文档会同步条目行状态。 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private static final Set<String> STATUSES = Set.of("pending", "approved", "rejected");

    private final DocumentManageService documentManageService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final KnowledgeEntryService knowledgeEntryService;
    private final DocumentBlobService documentBlobService;

    public DocumentController(DocumentManageService documentManageService, AuditService auditService,
                              NotificationService notificationService, KnowledgeEntryService knowledgeEntryService,
                              DocumentBlobService documentBlobService) {
        this.documentManageService = documentManageService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.knowledgeEntryService = knowledgeEntryService;
        this.documentBlobService = documentBlobService;
    }

    /**
     * 已入库文档清单：source、块数、visibility、审批状态与版本号；department 可选，不传只列全员可见的。
     * status 可选筛待审批/已发布/已驳回；普通用户与编辑只看已发布的，管理员与部门管理员看全部。
     * 分页：page 从 0 起，size 1-100，默认 20；越界报 400。
     */
    @GetMapping("/documents")
    public DocumentListResponse documents(@RequestParam(value = "department", required = false) String department,
                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size,
                                          @RequestParam(value = "status", required = false) String status) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 从 0 起，size 仅允许 1-100");
        }
        if (status != null && !STATUSES.contains(status.trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只允许 pending、approved、rejected");
        }
        UserPrincipal user = DepartmentScope.currentUser();
        String requested = department == null ? null : Visibility.normalize(department);
        String effectiveDepartment = DepartmentScope.effectiveDepartment(user, requested);
        boolean includePending = user != null && (user.isAdmin() || user.isDeptAdmin());
        return documentManageService.listDocuments(effectiveDepartment, page, size, status, includePending);
    }

    /** 删除指定文档的全部块：管理员可删全部，部门管理员只删本部门的；条目来源的连条目行一起删。 */
    @DeleteMapping("/document")
    public DeleteDocumentResponse deleteDocument(@RequestParam("source") String source) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        String visibility = requireDocumentVisibility(source);
        DepartmentScope.requireCanDeleteDocument(user, visibility);
        DeleteDocumentResponse response = documentManageService.deleteDocument(source);
        knowledgeEntryService.deleteBySource(source);
        auditService.record(user.username(), "DELETE_DOC", source, "deletedChunks=" + response.getDeletedChunks());
        return response;
    }

    /** 批量删文档：先统一校验全部权限，任一越权整体 403；再逐条删，返回成功与失败清单（非原子）。 */
    @PostMapping("/document/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody DocumentBatchDeleteRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<String> sources = requireSources(request);
        for (String source : sources) {
            DepartmentScope.requireCanDeleteDocument(user, requireDocumentVisibility(source));
        }
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                DeleteDocumentResponse response = documentManageService.deleteDocument(source);
                knowledgeEntryService.deleteBySource(source);
                auditService.record(user.username(), "DELETE_DOC", source,
                        "deletedChunks=" + response.getDeletedChunks());
                deleted.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "deleted", deleted, "failed", failed);
    }

    /** 批准文档发布：整篇文档的块统一改成已发布，问答开始能查到；条目来源同步条目行状态。 */
    @PostMapping("/document/approve")
    public Map<String, Object> approveDocument(@RequestParam("source") String source) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        return review(source, "approved", null, user);
    }

    /** 驳回文档：可带 reason，理由写进审计与上传人通知；条目来源同步条目行状态。 */
    @PostMapping("/document/reject")
    public Map<String, Object> rejectDocument(@RequestParam("source") String source,
                                              @RequestParam(value = "reason", required = false) String reason) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        return review(source, "rejected", reason, user);
    }

    /** 单条审批的公共执行：改状态、同步条目、记审计（含驳回理由）、发通知。 */
    private Map<String, Object> review(String source, String status, String reason, UserPrincipal user) {
        int chunks = documentManageService.setStatus(source, status);
        knowledgeEntryService.syncStatusBySource(source, status);
        String detail = "chunks=" + chunks;
        if (reason != null && !reason.isBlank()) {
            detail = detail + ",reason=" + reason.trim();
        }
        auditService.record(user.username(), "approved".equals(status) ? "APPROVE" : "REJECT", source, detail);
        notificationService.notifyDecision(source, user.username(), "approved".equals(status), reason);
        return Map.of("source", source, "status", status, "chunks", chunks);
    }

    /** 批量审批：先统一校验全部权限，任一越权整体 403；再逐条走单条审批（非原子）。 */
    @PostMapping("/document/batch-review")
    public Map<String, Object> batchReview(@RequestBody DocumentBatchReviewRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<String> sources = requireSources(request.getSources());
        if (request.getStatus() == null || !Set.of("approved", "rejected").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只允许 approved 或 rejected");
        }
        for (String source : sources) {
            DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        }
        List<String> reviewed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                review(source, request.getStatus(), request.getReason(), user);
                reviewed.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "reviewed", reviewed, "failed", failed);
    }

    /** 批量改可见性：先统一校验全部权限与新代号合法性，任一越权整体 403；再逐条改现行块（非原子）。 */
    @PostMapping("/document/batch-visibility")
    public Map<String, Object> batchVisibility(@RequestBody DocumentBatchVisibilityRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<String> sources = requireSources(request.getSources());
        if (request.getVisibility() == null || request.getVisibility().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility 不能为空");
        }
        String visibility = Visibility.normalize(request.getVisibility());
        for (String source : sources) {
            DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        }
        List<String> updated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                int chunks = documentManageService.updateVisibility(source, visibility);
                auditService.record(user.username(), "VISIBILITY", source, "visibility=" + visibility + ",chunks=" + chunks);
                updated.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "updated", updated, "failed", failed);
    }

    /** 回滚到指定旧版：目标版恢复成被替换之前的状态，当前版改成 superseded；条目来源按恢复后的状态同步。 */
    @PostMapping("/document/rollback")
    public Map<String, Object> rollbackDocument(@RequestParam("source") String source,
                                                @RequestParam("version") int version) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        String visibility = requireDocumentVisibility(source);
        DepartmentScope.requireCanApprove(user, visibility);
        int chunks = documentManageService.rollback(source, version);
        knowledgeEntryService.syncStatusBySource(source, documentManageService.currentStatus(source));
        auditService.record(user.username(), "ROLLBACK", source, "version=" + version + ",chunks=" + chunks);
        return Map.of("source", source, "version", version, "chunks", chunks);
    }

    /** 某篇文档的全部版本号（回滚下拉框用）：管理员与本部门管理员可看。 */
    @GetMapping("/document/versions")
    public Map<String, Object> documentVersions(@RequestParam("source") String source) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        String visibility = requireDocumentVisibility(source);
        DepartmentScope.requireCanApprove(user, visibility);
        return Map.of("source", source, "versions", documentManageService.listVersions(source));
    }

    /** 下载原始文档：按 source 与版本取上传时的原件字节；只本人可见范围内的文档能下。 */
    @GetMapping("/document/download")
    public ResponseEntity<byte[]> download(@RequestParam("source") String source,
                                           @RequestParam(value = "version", required = false) Integer version) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        String visibility = requireDocumentVisibility(source);
        requireCanRead(user, visibility);
        int target = version == null ? documentManageService.currentVersion(source) : version;
        DocumentBlobService.Blob blob = documentBlobService.find(source, target);
        if (blob == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该版本没有原始文件（可能是历史上传，未保存原件）");
        }
        String contentType = blob.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : blob.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodeFileName(blob.filename()))
                .contentType(MediaType.parseMediaType(contentType))
                .body(blob.bytes());
    }

    /** 在线文本预览：按块号拼回全文；传 version 可取指定旧版，供版本对比。 */
    @GetMapping("/document/preview")
    public Map<String, Object> preview(@RequestParam("source") String source,
                                       @RequestParam(value = "version", required = false) Integer version) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        requireCanRead(user, requireDocumentVisibility(source));
        return Map.of("source", source, "version", version == null ? "current" : version,
                "text", documentManageService.previewText(source, version));
    }

    /** 读权限：管理员或文档 all / 本人部门可见；部门管理员也能读本部门。 */
    private void requireCanRead(UserPrincipal user, String visibility) {
        if (user.isAdmin() || Visibility.ALL.equals(visibility)
                || (user.department() != null && user.department().equals(visibility))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该文档");
    }

    /** Content-Disposition 文件名：仅保留安全字符，中文按 UTF-8 百分号编码。 */
    private String encodeFileName(String name) {
        return java.net.URLEncoder.encode(name == null ? "document" : name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /** 批量入参校验：列表不能为空、不能超 50 条，去空白后保序去重。 */
    private List<String> requireSources(DocumentBatchDeleteRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sources 不能为空");
        }
        return requireSources(request.getSources());
    }

    private List<String> requireSources(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sources 不能为空");
        }
        List<String> sources = raw.stream()
                .filter(source -> source != null && !source.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (sources.isEmpty() || sources.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sources 需为 1-50 个有效文件名");
        }
        return sources;
    }

    private String requireDocumentVisibility(String source) {
        String visibility = documentManageService.getVisibility(source);
        if (visibility == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return visibility;
    }
}
