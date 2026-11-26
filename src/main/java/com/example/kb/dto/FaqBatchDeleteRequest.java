package com.example.kb.dto;

import java.util.List;
import java.util.UUID;

/** POST /api/faq/batch-delete 入参：要删除的标准答案 id 列表。 */
public class FaqBatchDeleteRequest {

    private List<UUID> ids;

    public List<UUID> getIds() {
        return ids;
    }

    public void setIds(List<UUID> ids) {
        this.ids = ids;
    }
}
