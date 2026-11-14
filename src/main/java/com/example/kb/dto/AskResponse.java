package com.example.kb.dto;

import java.util.List;

/**
 * /api/ask 出参：回答与引用来源列表（第 i 个来源对应回答中的 [i]）。
 */
public class AskResponse {

    private String answer;
    private List<String> sources;
    private List<ReferenceHit> hits;

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

    public List<ReferenceHit> getHits() {
        return hits;
    }

    public void setHits(List<ReferenceHit> hits) {
        this.hits = hits;
    }
}
