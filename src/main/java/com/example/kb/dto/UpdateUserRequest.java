package com.example.kb.dto;

/** 改用户：role 与 department 都可选，不传保持原值；department 传空串表示清空。 */
public class UpdateUserRequest {

    private String role;
    private String department;

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
