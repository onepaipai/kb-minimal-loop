package com.example.kb.security;

/** 登录用户的身份：用户名、角色（ADMIN/USER）、归属部门（null 表示管理员，无部门归属）。 */
public record UserPrincipal(String username, String role, String department) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
