package com.example.kb.dto;

import java.util.List;

/** POST /api/document/batch-delete 入参：要删除的 source 列表。 */
public class DocumentBatchDeleteRequest {

    private List<String> sources;

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }
}
