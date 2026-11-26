package com.example.kb.controller;

import java.util.List;

import com.example.kb.common.Visibility;
import com.example.kb.dto.AskRequest;
import com.example.kb.dto.ReferenceHit;
import com.example.kb.security.DepartmentScope;
import com.example.kb.service.AuditService;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 独立检索接口：只返回命中块（含原文与分数），不调聊天大模型生成答案。
 * 与问答共用 RagService 的权限过滤与混合检索，向量化与重排仍走模型（检索必需）。
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final RagService ragService;
    private final AuditService auditService;

    public SearchController(RagService ragService, AuditService auditService) {
        this.ragService = ragService;
        this.auditService = auditService;
    }

    /** 纯检索：question 必填，sources/department/maxResults/minScore 与问答同规则；返回编号命中块。 */
    @PostMapping("/search")
    public List<ReferenceHit> search(@RequestBody AskRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        if (request.getDepartment() != null) {
            request.setDepartment(Visibility.normalize(request.getDepartment()));
        }
        if (request.getMaxResults() != null && (request.getMaxResults() < 1 || request.getMaxResults() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxResults 仅允许 1-20");
        }
        if (request.getMinScore() != null && (request.getMinScore() < 0 || request.getMinScore() > 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minScore 仅允许 0-1");
        }
        RagService.AskScope scope = DepartmentScope.askScope(DepartmentScope.currentUser(), request.getDepartment());
        List<ReferenceHit> hits = ragService.searchHits(request, scope);
        auditService.record(scope.username(), "SEARCH", request.getQuestion(), "hits=" + hits.size());
        return hits;
    }
}
