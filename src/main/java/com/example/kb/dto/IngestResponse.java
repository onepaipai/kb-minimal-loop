package com.example.kb.dto;

/**
 * /api/ingest 出参：文件名、切块数量、可见性与审批状态（approved 直接发布，pending 待审批）。
 */
public class IngestResponse {

    private String file;
    private int chunks;
    private String visibility;
    private String status;
    /** 同名同内容再次上传时为 true：这次没重新入库，chunks 是已入库的块数 */
    private boolean skipped;

    public static IngestResponse of(String file, int chunks, String visibility, String status) {
        IngestResponse response = new IngestResponse();
        response.setFile(file);
        response.setChunks(chunks);
        response.setVisibility(visibility);
        response.setStatus(status);
        return response;
    }

    /** 同名同内容再次上传：不重新入库，chunks 为已入库的块数，skipped 标记这次没干活。 */
    public static IngestResponse skipped(String file, int chunks, String visibility, String status) {
        IngestResponse response = of(file, chunks, visibility, status);
        response.setSkipped(true);
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public void setSkipped(boolean skipped) {
        this.skipped = skipped;
    }
}
