package com.example.kb.dto;

/** POST /api/admin/users 入参：role 不传默认 USER，department 不传表示管理员（无归属部门）。 */
public class CreateUserRequest {

    private String username;
    private String password;
    private String role;
    private String department;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
