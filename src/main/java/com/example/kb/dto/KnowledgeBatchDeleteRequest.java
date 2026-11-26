package com.example.kb.dto;

import java.util.List;
import java.util.UUID;

/** POST /api/knowledge/batch-delete 入参：要删除的知识条目 id 列表。 */
public class KnowledgeBatchDeleteRequest {

    private List<UUID> ids;

    public List<UUID> getIds() {
        return ids;
    }

    public void setIds(List<UUID> ids) {
        this.ids = ids;
    }
}
