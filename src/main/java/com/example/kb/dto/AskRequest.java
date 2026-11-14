package com.example.kb.dto;

/**
 * /api/ask 入参：用户问题。
 */
public class AskRequest {

    private String question;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
