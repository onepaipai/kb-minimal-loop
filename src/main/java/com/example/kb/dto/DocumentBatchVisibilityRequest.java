package com.example.kb.dto;

import java.util.List;

/** POST /api/document/batch-visibility 入参：批量改可见性的 source 列表与目标部门代号。 */
public class DocumentBatchVisibilityRequest {

    private List<String> sources;
    private String visibility;

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
