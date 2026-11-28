package com.example.kb.service;

import com.example.kb.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档批量操作与单条审批的公共执行层：Controller 只做入参校验与权限预检，
 * 具体的状态变更、条目同步、审计与通知集中在这里，避免单条/批量两套逻辑漂移。
 */
@Service
public class DocumentBatchService {

    private final DocumentManageService documentManageService;
    private final KnowledgeEntryService knowledgeEntryService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public DocumentBatchService(DocumentManageService documentManageService,
                                KnowledgeEntryService knowledgeEntryService, AuditService auditService,
                                NotificationService notificationService) {
        this.documentManageService = documentManageService;
        this.knowledgeEntryService = knowledgeEntryService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /** 单条审批：改状态、同步条目行、记审计（含驳回理由）、发通知。 */
    @Transactional
    public Map<String, Object> reviewOne(String source, String status, String reason, UserPrincipal user) {
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

    /** 批量移入回收站：保留块与关联条目，返回成功/失败清单（非原子）。 */
    public Map<String, Object> delete(List<String> sources, UserPrincipal user) {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                int chunks = documentManageService.deleteDocument(source, user.username()).getDeletedChunks();
                auditService.record(user.username(), "DELETE_DOC", source, "deletedChunks=" + chunks);
                deleted.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "deleted", deleted, "failed", failed);
    }

    /** 批量恢复回收站文档：恢复删除前状态并同步知识条目状态。 */
    public Map<String, Object> restore(List<String> sources, UserPrincipal user) {
        List<String> restored = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                int versions = documentManageService.restoreDocument(source);
                knowledgeEntryService.syncStatusBySource(source, documentManageService.currentStatus(source));
                auditService.record(user.username(), "RESTORE_DOC", source, "restoredVersions=" + versions);
                restored.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "restored", restored, "failed", failed);
    }

    /** 批量永久删除回收站文档：清理原件、块、版本和关联条目。 */
    public Map<String, Object> permanentlyDelete(List<String> sources, UserPrincipal user) {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                int chunks = documentManageService.permanentlyDeleteDocument(source).getDeletedChunks();
                knowledgeEntryService.deleteBySource(source);
                auditService.record(user.username(), "PURGE_DOC", source, "deletedChunks=" + chunks);
                deleted.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "deleted", deleted, "failed", failed);
    }

    /** 批量审批：逐条复用 reviewOne，返回成功/失败清单（非原子）。 */
    public Map<String, Object> review(List<String> sources, String status, String reason, UserPrincipal user) {
        List<String> reviewed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                reviewOne(source, status, reason, user);
                reviewed.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "reviewed", reviewed, "failed", failed);
    }

    /** 批量改可见性：只改现行块，返回成功/失败清单（非原子）。 */
    @Transactional
    public Map<String, Object> updateVisibility(List<String> sources, String visibility, UserPrincipal user) {
        List<String> updated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String source : sources) {
            try {
                int chunks = documentManageService.updateVisibility(source, visibility);
                auditService.record(user.username(), "VISIBILITY", source,
                        "visibility=" + visibility + ",chunks=" + chunks);
                updated.add(source);
            } catch (Exception e) {
                failed.add(source + ": " + e.getMessage());
            }
        }
        return Map.of("requested", sources.size(), "updated", updated, "failed", failed);
    }
}
