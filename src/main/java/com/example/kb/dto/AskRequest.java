package com.example.kb.dto;

import java.util.List;

/**
 * /api/ask 入参：用户问题；sources 可选，限定只在指定文档内检索，为空则全库检索。
 */
public class AskRequest {

    private String question;
    private List<String> sources;

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
}
