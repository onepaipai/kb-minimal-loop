package com.example.kb.dto;

import java.time.OffsetDateTime;

/** 回收站文档摘要：保留恢复所需的版本、权限和删除信息。 */
public class DocumentTrashSummary {

    private String source;
    private int chunks;
    private String visibility;
    private String status;
    private int version;
    private boolean hasBlob;
    private OffsetDateTime deletedAt;
    private String deletedBy;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getChunks() {
        return chunks;
    }

    public void setChunks(int chunks) {
        this.chunks = chunks;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isHasBlob() {
        return hasBlob;
    }

    public void setHasBlob(boolean hasBlob) {
        this.hasBlob = hasBlob;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(OffsetDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(String deletedBy) {
        this.deletedBy = deletedBy;
    }
}
