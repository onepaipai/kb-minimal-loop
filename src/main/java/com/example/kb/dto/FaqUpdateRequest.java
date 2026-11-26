package com.example.kb.dto;

/**
 * PUT /api/faq/{id} 入参：修改标准答案，字段为 null 的不更新。
 */
public class FaqUpdateRequest {

    private String answer;
    private String keywords;
    private String department;
    private Boolean enabled;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
