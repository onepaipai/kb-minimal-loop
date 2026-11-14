package com.example.kb.dto;

import java.util.List;

/**
 * /api/ask 出参：回答、去重后的引用来源列表与逐条引用详情（编号对应关系看 hits）。
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
