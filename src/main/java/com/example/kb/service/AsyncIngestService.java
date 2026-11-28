package com.example.kb.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

/** 异步入库提交：建一个 PENDING 任务交给后台线程，立即返回任务号。 */
@Service
public class AsyncIngestService {

    private final IngestJobStore jobStore;
    private final IngestJobRunner jobRunner;

    public AsyncIngestService(IngestJobStore jobStore, IngestJobRunner jobRunner) {
        this.jobStore = jobStore;
        this.jobRunner = jobRunner;
    }

    public IngestJobStore.IngestJob submit(byte[] bytes, String fileName, boolean isJava,
                                           String visibility, String status, String username) {
        IngestJobStore.IngestJob job = jobStore.createWithPayload(fileName, username, visibility, isJava, status, bytes);
        try {
            jobRunner.run(job.id());
        } catch (RuntimeException error) {
            jobStore.updateStatus(job.id(), "FAILED", null, "任务提交失败：" + error.getMessage());
            throw error;
        }
        return job;
    }
}
