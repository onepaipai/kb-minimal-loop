package com.example.kb.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 站内通知接口：自己的通知列表、未读数、标已读。 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 自己的通知：最新在前；page 从 0 起，size 1-100，默认 20。 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 从 0 起，size 仅允许 1-100");
        }
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        List<NotificationService.NotificationItem> notifications =
                notificationService.list(user.username(), page, size);
        return Map.of(
                "notifications", notifications,
                "total", notificationService.total(user.username()),
                "unread", notificationService.unreadCount(user.username()),
                "page", page,
                "size", size);
    }

    /** 标一条为已读：不存在或不是自己的报 404。 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable UUID id) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        if (!notificationService.markRead(user.username(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        return Map.of("id", id.toString(), "read", true);
    }

    /** 全部标已读，返回改动的条数。 */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        return Map.of("updated", notificationService.markAllRead(user.username()));
    }
}
