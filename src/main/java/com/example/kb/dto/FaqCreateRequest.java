package com.example.kb.dto;

/**
 * POST /api/faq 入参：新建标准答案。
 */
public class FaqCreateRequest {

    private String question;
    private String answer;
    /** 可选：逗号分隔的匹配关键词，问题里包含任一关键词也算命中 */
    private String keywords;
    /** 可选：生效部门代号，不传为 all 全员生效 */
    private String department;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

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
}
