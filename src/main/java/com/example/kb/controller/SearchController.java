package com.example.kb.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.kb.common.RequestValidation;
import com.example.kb.common.Visibility;
import com.example.kb.dto.AskRequest;
import com.example.kb.dto.ReferenceHit;
import com.example.kb.security.DepartmentScope;
import com.example.kb.service.AuditService;
import com.example.kb.service.HybridSearchService;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 独立检索接口：只返回命中块（含原文与分数），不调聊天大模型生成答案。
 * 与问答共用 RagService 的检索主干，向量化与重排仍走模型（检索必需）。
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

    /** 纯检索：返回编号命中块，每条带 matchedTerms 供前端高亮。 */
    @PostMapping("/search")
    public List<ReferenceHit> search(@RequestBody AskRequest request) {
        List<ReferenceHit> hits = doSearch(request);
        RagService.AskScope scope = DepartmentScope.askScope(DepartmentScope.currentUser(), request.getDepartment());
        auditService.record(scope.username(), "SEARCH", request.getQuestion(), "hits=" + hits.size());
        return hits;
    }

    /** 按来源分组的检索结果：前端"仅搜索"按文档折叠展示。 */
    @PostMapping("/search/grouped")
    public Map<String, List<ReferenceHit>> searchGrouped(@RequestBody AskRequest request) {
        List<ReferenceHit> hits = doSearch(request);
        Map<String, List<ReferenceHit>> grouped = new LinkedHashMap<>();
        for (ReferenceHit hit : hits) {
            grouped.computeIfAbsent(hit.getSource() == null ? "未知来源" : hit.getSource(),
                    key -> new ArrayList<>()).add(hit);
        }
        RagService.AskScope scope = DepartmentScope.askScope(DepartmentScope.currentUser(), request.getDepartment());
        auditService.record(scope.username(), "SEARCH_GROUPED", request.getQuestion(), "hits=" + hits.size());
        return grouped;
    }

    /** 两个公开接口共用的校验与检索：入口统一归一 department、校验越界参数。 */
    private List<ReferenceHit> doSearch(AskRequest request) {
        RequestValidation.validateAsk(request);
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
        List<String> terms = HybridSearchService.tokenize(request.getQuestion());
        for (ReferenceHit hit : hits) {
            hit.setMatchedTerms(terms);
        }
        return hits;
    }
}
