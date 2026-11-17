package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 异步入库任务：上传立即返回任务，解析与向量化在后台线程做，前端轮询任务状态。 */
public interface IngestJobStore {

    record IngestJob(UUID id, String source, String username, String visibility, String status,
                     int chunks, String error, OffsetDateTime createdAt) {
    }

    IngestJob create(String source, String username, String visibility);

    Optional<IngestJob> find(UUID id);

    void updateStatus(UUID id, String status, Integer chunks, String error);

    void deleteOld();
}
