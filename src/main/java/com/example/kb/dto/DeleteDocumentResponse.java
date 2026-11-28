package com.example.kb.dto;

/**
 * DELETE /api/document 出参：source、移入回收站的块数与是否确实完成软删除。
 */
public class DeleteDocumentResponse {

    private String source;
    private boolean deleted;
    private int deletedChunks;

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

    public int getDeletedChunks() {
        return deletedChunks;
    }

    public void setDeletedChunks(int deletedChunks) {
        this.deletedChunks = deletedChunks;
    }
}
