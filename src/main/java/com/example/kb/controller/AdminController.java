package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.CreateUserRequest;
import com.example.kb.security.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员接口：创建用户、查用户清单；仅 ADMIN 角色能调。 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    /** 创建用户：用户名已存在报 409，role 只允许 ADMIN/USER，密码至少 8 位。 */
    @PostMapping("/users")
    public UserService.UserSummary createUser(@RequestBody CreateUserRequest request) {
        return userService.createUser(request.getUsername(), request.getPassword(),
                request.getRole(), request.getDepartment());
    }

    /** 用户清单：不含密码 hash。 */
    @GetMapping("/users")
    public List<UserService.UserSummary> listUsers() {
        return userService.listUsers();
    }
}
