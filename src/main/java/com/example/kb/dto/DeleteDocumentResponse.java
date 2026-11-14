package com.example.kb.dto;

/**
 * DELETE /api/document 出参：被删除的 source 与是否确有删除。
 */
public class DeleteDocumentResponse {

    private String source;
    private boolean deleted;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
