package com.example.kb.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.kb.common.Visibility;
import com.example.kb.dto.DocumentBatchDeleteRequest;
import com.example.kb.dto.DocumentBatchReviewRequest;
import com.example.kb.dto.DocumentBatchVisibilityRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.DocumentBatchService;
import com.example.kb.service.DocumentManageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 文档批量操作接口：批量删除、批量审批、批量改可见性；先统一校验权限，再逐条执行（非原子）。 */
@RestController
@RequestMapping("/api")
public class DocumentBatchController {

    private final DocumentBatchService documentBatchService;
    private final DocumentManageService documentManageService;

    public DocumentBatchController(DocumentBatchService documentBatchService,
                                   DocumentManageService documentManageService) {
        this.documentBatchService = documentBatchService;
        this.documentManageService = documentManageService;
    }

    /** 批量删文档：任一越权整体 403，再逐条删。 */
    @PostMapping("/document/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody DocumentBatchDeleteRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<String> sources = requireSources(request == null ? null : request.getSources());
        for (String source : sources) {
            DepartmentScope.requireCanDeleteDocument(user, requireDocumentVisibility(source));
        }
        return documentBatchService.delete(sources, user);
    }

    /** 批量审批：status 只允许 approved/rejected，可带驳回理由。 */
    @PostMapping("/document/batch-review")
    public Map<String, Object> batchReview(@RequestBody DocumentBatchReviewRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        if (request == null || request.getStatus() == null
                || !Set.of("approved", "rejected").contains(request.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只允许 approved 或 rejected");
        }
        List<String> sources = requireSources(request.getSources());
        for (String source : sources) {
            DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        }
        return documentBatchService.review(sources, request.getStatus(), request.getReason(), user);
    }

    /** 批量改可见性：新代号走统一校验，任一越权整体 403。 */
    @PostMapping("/document/batch-visibility")
    public Map<String, Object> batchVisibility(@RequestBody DocumentBatchVisibilityRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        if (request == null || request.getVisibility() == null || request.getVisibility().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility 不能为空");
        }
        String visibility = Visibility.normalize(request.getVisibility());
        List<String> sources = requireSources(request.getSources());
        for (String source : sources) {
            DepartmentScope.requireCanApprove(user, requireDocumentVisibility(source));
        }
        return documentBatchService.updateVisibility(sources, visibility, user);
    }

    /** 批量入参校验：1-50 个有效 source，去空白后保序去重。 */
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
