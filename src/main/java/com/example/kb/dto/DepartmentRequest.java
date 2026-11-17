package com.example.kb.dto;

/** POST /api/admin/departments 入参：code 为部门代号，parentCode 为父部门代号（可空）。 */
public class DepartmentRequest {

    private String code;
    private String name;
    private String parentCode;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParentCode() {
        return parentCode;
    }

    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }
}
