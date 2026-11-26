package com.example.kb.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.kb.security.DepartmentScope;
import com.example.kb.service.ChatSessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 消息级操作：删单条消息、收藏/取消收藏、收藏清单；全部按当前用户名隔离。 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final ChatSessionService chatSessionService;

    public MessageController(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    /** 删除单条消息：别人的消息删不到。 */
    @DeleteMapping("/{messageId}")
    public Map<String, Object> deleteMessage(@PathVariable UUID messageId) {
        boolean deleted = chatSessionService.deleteMessage(
                DepartmentScope.requireCurrentUser().username(), messageId);
        return Map.of("deleted", deleted);
    }

    /** 收藏/取消收藏一条消息。 */
    @PutMapping("/{messageId}/star")
    public Map<String, Object> setStarred(@PathVariable UUID messageId,
                                          @RequestParam("starred") boolean starred) {
        boolean updated = chatSessionService.setStarred(
                DepartmentScope.requireCurrentUser().username(), messageId, starred);
        return Map.of("updated", updated);
    }

    /** 收藏清单：最近收藏的助手回答，跨会话。 */
    @GetMapping("/starred")
    public List<ChatSessionService.StarredItem> starred() {
        return chatSessionService.starred(DepartmentScope.requireCurrentUser().username());
    }
}
