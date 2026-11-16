package com.example.kb.dto;

/**
 * /api/ingest 出参：文件名与切块数量。
 */
public class IngestResponse {

    private String file;
    private int chunks;
    private String visibility;

    public static IngestResponse of(String file, int chunks, String visibility) {
        IngestResponse response = new IngestResponse();
        response.setFile(file);
        response.setChunks(chunks);
        response.setVisibility(visibility);
        return response;
    }

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

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
