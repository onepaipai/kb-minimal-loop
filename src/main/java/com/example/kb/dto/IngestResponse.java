package com.example.kb.dto;

/**
 * /api/ingest 出参：文件名与切块数量。
 */
public class IngestResponse {

    private String file;
    private int chunks;

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getChunks() {
        return chunks;
    }

    public void setChunks(int chunks) {
        this.chunks = chunks;
    }
}
