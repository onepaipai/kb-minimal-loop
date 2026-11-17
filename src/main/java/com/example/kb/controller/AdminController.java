package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.AuditLogListResponse;
import com.example.kb.dto.CreateUserRequest;
import com.example.kb.dto.UpdateUserRequest;
import com.example.kb.service.AuditService;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.security.UserService;
import com.example.kb.service.FeedbackService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 管理接口：创建用户、查用户清单、查审计日志、看问答反馈统计。
 * 用户管理：管理员管全部，部门管理员只管本部门的人；审计日志与反馈统计仅管理员能看。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserService userService;
    private final AuditService auditService;
    private final FeedbackService feedbackService;

    public AdminController(UserService userService, AuditService auditService, FeedbackService feedbackService) {
        this.userService = userService;
        this.auditService = auditService;
        this.feedbackService = feedbackService;
    }

    /** 创建用户：用户名已存在报 409，role 只允许四档，密码至少 10 位且含字母和数字；部门管理员只能建本部门的人。 */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public UserService.UserSummary createUser(@RequestBody CreateUserRequest request) {
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        String department = request.getDepartment();
        if (caller.isDeptAdmin()) {
            if (department != null && !department.equals(caller.department())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "部门管理员只能创建本部门的用户");
            }
            department = caller.department();
        }
        UserService.UserSummary created = userService.createUser(request.getUsername(), request.getPassword(),
                request.getRole(), department);
        auditService.record(caller.username(), "CREATE_USER", created.username(), "role=" + created.role());
        return created;
    }

    /** 用户清单：管理员看全部，部门管理员只看本部门的人；不含密码 hash。 */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public List<UserService.UserSummary> listUsers() {
        return userService.listUsers(DepartmentScope.currentUser());
    }

    /** 改用户角色与部门：仅管理员；不存在报 404，ADMIN 升上去时部门自动清空。 */
    @PutMapping("/users/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserService.UserSummary updateUser(@PathVariable String username, @RequestBody UpdateUserRequest request) {
        UserPrincipal caller = DepartmentScope.requireCurrentUser();
        UserService.UserSummary updated = userService.updateUser(username, request.getRole(), request.getDepartment());
        auditService.record(caller.username(), "UPDATE_USER", updated.username(),
                "role=" + updated.role() + " department=" + updated.department());
        return updated;
    }

    /** 审计日志：分页，最新在前；仅管理员。 */
    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public AuditLogListResponse auditLogs(@RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 从 0 起，size 仅允许 1-100");
        }
        AuditLogListResponse response = new AuditLogListResponse();
        response.setLogs(auditService.listLogs(page, size));
        response.setTotal(auditService.countLogs());
        response.setPage(page);
        response.setSize(size);
        return response;
    }

    /** 问答反馈统计：总数、好评数、好评率；仅管理员。 */
    @GetMapping("/feedback/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public FeedbackService.FeedbackStats feedbackStats() {
        return feedbackService.stats();
    }
}
