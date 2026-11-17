package com.example.kb.security;

/** 登录用户的身份：用户名、角色（ADMIN/DEPT_ADMIN/EDITOR/USER）、归属部门（null 表示管理员，无部门归属）。 */
public record UserPrincipal(String username, String role, String department) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isDeptAdmin() {
        return "DEPT_ADMIN".equals(role);
    }

    public boolean isEditor() {
        return "EDITOR".equals(role);
    }

    /** 能上传文档的角色：除普通用户外都可上传（上传后是否直接发布由审批规则定）。 */
    public boolean canUpload() {
        return !"USER".equals(role);
    }
}
