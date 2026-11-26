package com.example.kb.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.kb.common.Visibility;
import com.example.kb.dto.KnowledgeBatchDeleteRequest;
import com.example.kb.dto.KnowledgeEntryCreateRequest;
import com.example.kb.dto.KnowledgeEntryUpdateRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.KnowledgeEntryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识条目接口：在线撰写正文直接入库，不必做成文件上传。
 * 上传权限与可见性规则、审批状态与文件上传完全一致；清单与详情的可见范围与文档清单一致。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeEntryController {

    private static final Set<String> STATUSES = Set.of("pending", "approved", "rejected");

    private final KnowledgeEntryService knowledgeEntryService;

    public KnowledgeEntryController(KnowledgeEntryService knowledgeEntryService) {
        this.knowledgeEntryService = knowledgeEntryService;
    }

    /** 新建知识条目：标题与正文必填；普通用户不能写（与上传同权限），编辑写的待审批。 */
    @PostMapping
    public KnowledgeEntryService.KnowledgeEntrySaved create(@RequestBody KnowledgeEntryCreateRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文不能为空");
        }
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        return knowledgeEntryService.create(user, request.getTitle(), request.getContent(),
                request.getCategory(), request.getTags(), request.getVisibility());
    }

    /** 知识条目清单：department 可选（管理员用），status 可选筛状态；非管理视角只看已发布的。 */
    @GetMapping
    public List<KnowledgeEntryService.KnowledgeEntryItem> list(
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tag", required = false) String tag) {
        if (status != null && !STATUSES.contains(status.trim().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只允许 pending、approved、rejected");
        }
        UserPrincipal user = DepartmentScope.currentUser();
        String requested = null;
        if (department != null) {
            requested = Visibility.normalize(department);
        }
        String effectiveDepartment = DepartmentScope.effectiveDepartment(user, requested);
        boolean includePending = user != null && (user.isAdmin() || user.isDeptAdmin());
        // 非管理视角带上调用者：本人写的待审批条目也能在清单里看到（编辑不至于找不到自己刚写的条目）
        String callerUsername = null;
        if (user != null && !includePending) {
            callerUsername = user.username();
        }
        return knowledgeEntryService.list(effectiveDepartment, status, includePending, callerUsername, category, tag);
    }

    /** 条目详情（含正文）：编辑回填用；不可见的人按不存在处理。 */
    @GetMapping("/{id}")
    public KnowledgeEntryService.KnowledgeEntryDetail get(@PathVariable UUID id) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        return knowledgeEntryService.get(id, user);
    }

    /** 更新条目并重新入库：content/category/tags/visibility 为 null 的沿用原值；标题不可改。 */
    @PutMapping("/{id}")
    public KnowledgeEntryService.KnowledgeEntrySaved update(@PathVariable UUID id,
                                                            @RequestBody KnowledgeEntryUpdateRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        return knowledgeEntryService.update(id, user, request.getContent(), request.getCategory(),
                request.getTags(), request.getVisibility());
    }

    /** 删除条目：块（含旧版）与条目一起删；权限与文档删除一致。 */
    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> delete(@PathVariable UUID id) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        knowledgeEntryService.delete(id, user);
        return java.util.Map.of("deleted", true);
    }

    /** 批量删条目：先统一校验全部权限，任一越权整体 403；逐条删返回成功清单（非原子）。 */
    @PostMapping("/batch-delete")
    public java.util.Map<String, Object> batchDelete(@RequestBody KnowledgeBatchDeleteRequest request) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<UUID> ids = requireIds(request);
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            List<UUID> ok = knowledgeEntryService.batchDelete(ids, user);
            for (UUID id : ok) {
                deleted.add(String.valueOf(id));
            }
        } catch (Exception e) {
            for (UUID id : ids) {
                failed.add(String.valueOf(id) + ": " + e.getMessage());
            }
        }
        return java.util.Map.of("requested", ids.size(), "deleted", deleted, "failed", failed);
    }

    /** 批量入参校验：1-50 个不重复的 id。 */
    private List<UUID> requireIds(KnowledgeBatchDeleteRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 不能为空");
        }
        List<UUID> ids = request.getIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 需为 1-50 个有效 id");
        }
        return ids;
    }
}
