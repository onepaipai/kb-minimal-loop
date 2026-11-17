package com.example.kb.service;

import java.util.Locale;

import com.example.kb.dto.IngestResponse;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 异步入库的后台执行者：跑在 ingest 线程池里，成功记块数，失败记原因，旧块只在成功后才替换。 */
@Service
public class IngestJobRunner {

    private final IngestJobStore jobStore;
    private final IngestService ingestService;
    private final CodeIngestService codeIngestService;

    public IngestJobRunner(IngestJobStore jobStore, IngestService ingestService, CodeIngestService codeIngestService) {
        this.jobStore = jobStore;
        this.ingestService = ingestService;
        this.codeIngestService = codeIngestService;
    }

    @Async("ingestExecutor")
    public void run(java.util.UUID jobId, byte[] bytes, String fileName, boolean isJava,
                    String visibility, String status) {
        jobStore.updateStatus(jobId, "RUNNING", null, null);
        try {
            IngestResponse response = isJava
                    ? codeIngestService.ingestJavaBytes(bytes, fileName, visibility, status)
                    : ingestService.ingestBytes(bytes, fileName, visibility, status);
            jobStore.updateStatus(jobId, "DONE", response.getChunks(), null);
        } catch (ResponseStatusException e) {
            // 业务校验失败（空文件、扫描版 PDF、语法错的 .java）直接把原因记进任务
            jobStore.updateStatus(jobId, "FAILED", null, e.getReason());
        } catch (Exception e) {
            jobStore.updateStatus(jobId, "FAILED", null, "入库失败：" + e.getMessage());
        }
    }
}
