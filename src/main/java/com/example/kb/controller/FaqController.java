package com.example.kb.controller;

import java.util.List;
import java.util.UUID;

import com.example.kb.common.Visibility;
import com.example.kb.dto.FaqCreateRequest;
import com.example.kb.dto.FaqUpdateRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.AuditService;
import com.example.kb.service.FaqService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 标准答案（置顶 FAQ）管理接口：命中后问答直接返回，不走检索。
 * 管理员管全部，部门管理员只管本部门的（all 全员的归管理员）。
 */
@RestController
@RequestMapping("/api/faq")
public class FaqController {

    private final FaqService faqService;
    private final AuditService auditService;

    public FaqController(FaqService faqService, AuditService auditService) {
        this.faqService = faqService;
        this.auditService = auditService;
    }

    /** 新建标准答案：question 与 answer 必填且有长度上限；部门管理员强制生效部门为本部门，管理员默认 all。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public FaqService.FaqEntry create(@RequestBody FaqCreateRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        if (request.getQuestion().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 最长 200 字");
        }
        if (request.getAnswer() == null || request.getAnswer().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer 不能为空");
        }
        if (request.getAnswer().length() > 5000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer 最长 5000 字");
        }
        if (request.getKeywords() != null && request.getKeywords().length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keywords 最长 200 字");
        }
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        String department = resolveManageDepartment(caller, request.getDepartment());
        FaqService.FaqEntry created = faqService.create(request.getQuestion(), request.getAnswer(),
                request.getKeywords(), department, caller.username());
        auditService.record(caller.username(), "CREATE_FAQ", created.question(), "department=" + department);
        return created;
    }

    /** 标准答案清单：管理员看全部，部门管理员只看本部门的。 */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public List<FaqService.FaqEntry> list() {
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        String department = null;
        if (!caller.isAdmin()) {
            department = caller.department();
        }
        return faqService.list(department);
    }

    /** 修改标准答案：answer/keywords/department/enabled 为 null 的不更新；部门管理员改不了别的部门的。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public FaqService.FaqEntry update(@PathVariable UUID id, @RequestBody FaqUpdateRequest request) {
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        requireCanManageEntry(caller, faqService.get(id).department());
        String department = null;
        if (request.getDepartment() != null) {
            department = resolveManageDepartment(caller, request.getDepartment());
        }
        FaqService.FaqEntry updated = faqService.update(id, request.getAnswer(), request.getKeywords(),
                department, request.getEnabled());
        auditService.record(caller.username(), "UPDATE_FAQ", updated.question(), null);
        return updated;
    }

    /** 删除标准答案：部门管理员删不了别的部门的。 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public java.util.Map<String, Object> delete(@PathVariable UUID id) {
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        requireCanManageEntry(caller, faqService.get(id).department());
        boolean deleted = faqService.delete(id);
        auditService.record(caller.username(), "DELETE_FAQ", String.valueOf(id), null);
        return java.util.Map.of("deleted", deleted);
    }

    /**
     * 生效部门解析：管理员可指定任意合法代号（留空 all）；部门管理员强制本部门。
     */
    private String resolveManageDepartment(UserPrincipal caller, String requested) {
        if (caller.isDeptAdmin()) {
            return caller.department();
        }
        if (requested == null || requested.isBlank()) {
            return Visibility.ALL;
        }
        return Visibility.normalize(requested);
    }

    /** 行级权限：部门管理员只能动本部门的条目。 */
    private void requireCanManageEntry(UserPrincipal caller, String entryDepartment) {
        if (caller.isAdmin()) {
            return;
        }
        if (caller.isDeptAdmin() && caller.department() != null
                && caller.department().equals(entryDepartment)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能管理本部门的标准答案");
    }
}
