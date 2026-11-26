package com.example.kb.controller;

import java.util.List;

import com.example.kb.security.DepartmentScope;
import com.example.kb.service.ChatSessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return chatSessionService.messages(DepartmentScope.requireCurrentUser().username(), sessionId);
    }

    /** 删除会话与它的全部消息。 */
    @DeleteMapping("/{sessionId}")
    public java.util.Map<String, Object> delete(@PathVariable String sessionId) {
        boolean deleted = chatSessionService.delete(DepartmentScope.requireCurrentUser().username(), sessionId);
        return java.util.Map.of("deleted", deleted);
    }
}
