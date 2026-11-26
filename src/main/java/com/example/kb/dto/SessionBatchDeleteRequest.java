package com.example.kb.dto;

import java.util.List;

/** POST /api/sessions/batch-delete 入参：要删除的会话 id 列表。 */
public class SessionBatchDeleteRequest {

    private List<String> ids;

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }
}
