package com.example.kb.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.example.kb.dto.SessionBatchDeleteRequest;
import com.example.kb.security.DepartmentScope;
import com.example.kb.service.ChatSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 会话历史接口：我的会话清单、会话消息回放、删除会话；只查自己的，跨设备同步。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final ChatSessionService chatSessionService;

    public SessionController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    /** 我的会话清单：最近更新在前，上限 100 个。 */
    @GetMapping
    public List<ChatSessionService.SessionItem> list() {
        return chatSessionService.list(DepartmentScope.requireCurrentUser().username());
    }

    /** 某个会话的消息回放：时间正序，只查自己的；别人的会话查不到（返回空列表）。 */
    @GetMapping("/{sessionId}/messages")
    public List<ChatSessionService.MessageItem> messages(@PathVariable String sessionId) {
        return chatSessionService.messages(DepartmentScope.requireCurrentUser().username(), requireSessionId(sessionId));
    }

    /** 删除会话与它的全部消息。 */
    @DeleteMapping("/{sessionId}")
    public java.util.Map<String, Object> delete(@PathVariable String sessionId) {
        boolean deleted = chatSessionService.delete(DepartmentScope.requireCurrentUser().username(), requireSessionId(sessionId));
        return java.util.Map.of("deleted", deleted);
    }

    /** 批量删会话：按 username 天然隔离，逐条删返回成功清单（非原子）。 */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody SessionBatchDeleteRequest request) {
        String username = DepartmentScope.requireCurrentUser().username();
        List<String> ids = requireIds(request);
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (chatSessionService.delete(username, id)) {
                deleted.add(id);
            } else {
                failed.add(id + ": 不存在");
            }
        }
        return Map.of("requested", ids.size(), "deleted", deleted, "failed", failed);
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 长度必须为 1-100 字符");
        }
        return sessionId.trim();
    }

    /** 批量入参校验：1-50 个不重复的会话 id。 */
    private List<String> requireIds(SessionBatchDeleteRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 不能为空");
        }
        List<String> ids = request.getIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (ids.stream().anyMatch(id -> id.length() > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 最长 100 字符");
        }
        if (ids.isEmpty() || ids.size() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 需为 1-50 个有效会话号");
        }
        return ids;
    }
}
