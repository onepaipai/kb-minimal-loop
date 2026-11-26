package com.example.kb.dto;

/**
 * 单个已入库文档的摘要：source、块数、可见性、审批状态与版本号。
 */
public class DocumentSummary {

    private String source;
    private int chunks;
    private String visibility;
    private String status;
    private int version;
    private boolean hasBlob;

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
}
