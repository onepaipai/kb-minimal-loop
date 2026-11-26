package com.example.kb.dto;

import java.util.List;

/** POST /api/document/batch-review 入参：批量审批的 source 列表、目标状态与可选驳回理由。 */
public class DocumentBatchReviewRequest {

    private List<String> sources;
    private String status;
    private String reason;

    public List<String> getSources() {
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = sources;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
