package com.example.kb.dto;

import java.util.List;

/**
 * /api/ask 入参：用户问题；sources 可选，限定只在指定文档内检索，为空则全库检索。
 */
public class AskRequest {

    private String question;
    private List<String> sources;
    /** 可选：多轮会话标识，传入后该会话的问答历史会被保留用于追问 */
    private String sessionId;
    /** 可选：最大检索块数，不传用默认 5 */
    private Integer maxResults;
    /** 可选：最低相关度分数（0~1），不传用默认 0.55 */
    private Double minScore;
    /** 可选：部门代号，不传只查全员可见的块 */
    private String department;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }
}
