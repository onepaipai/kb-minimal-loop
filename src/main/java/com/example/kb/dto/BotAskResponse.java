package com.example.kb.dto;

import java.util.List;

/**
 * POST /api/bot/ask 出参（通用格式）：答案与引用来源。
 */
public class BotAskResponse {

    private String answer;
    private List<String> sources;

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }
}
