package com.example.kb.dto;

/**
 * 单个已入库文档的摘要：source 与块数。
 */
public class DocumentSummary {

    private String source;
    private int chunks;
    private String visibility;

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
}
