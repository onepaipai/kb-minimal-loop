package com.example.kb.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.kb.dto.ApiKeyCreateRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.ApiKeyService;
import com.example.kb.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 对外 API Key 管理接口（仅管理员）：创建、列表、吊销。
 * Key 归创建者所有，权限就是创建者的角色；明文只在创建时返回一次，之后查不到。
 */
@RestController
@RequestMapping("/api/admin/api-keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final AuditService auditService;

    public ApiKeyController(ApiKeyService apiKeyService, AuditService auditService) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
    }

    /** 建 Key：名字 1-64 字；返回的 key 明文只出现这一次，前端展示后即丢。 */
    @PostMapping
    public Map<String, Object> create(@RequestBody ApiKeyCreateRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名字 1-64 字");
        }
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        ApiKeyService.CreatedKey created = apiKeyService.create(name, user.username());
        auditService.record(user.username(), "CREATE_API_KEY", created.id().toString(), "name=" + name);
        return Map.of("id", created.id().toString(), "key", created.rawKey());
    }

    /** 全部 Key 列表：不含明文。 */
    @GetMapping
    public List<ApiKeyService.ApiKeyItem> list() {
        return apiKeyService.list();
    }

    /** 吊销：不存在或已吊销报 404。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@PathVariable UUID id) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        if (!apiKeyService.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API Key 不存在或已吊销");
        }
        auditService.record(user.username(), "REVOKE_API_KEY", id.toString(), "");
        return Map.of("id", id.toString(), "revoked", true);
    }
}
