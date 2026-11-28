package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 异步入库任务：上传立即返回任务，解析与向量化在后台线程做，前端轮询任务状态。 */
public interface IngestJobStore {

    record IngestJob(UUID id, String source, String username, String visibility, String status,
                     int chunks, String error, OffsetDateTime createdAt) {
    }

    record JobInput(byte[] bytes, String source, boolean javaFile, String visibility, String targetStatus) {
    }

    IngestJob create(String source, String username, String visibility);

    /** 持久化异步任务的输入；旧的内存测试实现仍可使用 create 默认实现。 */
    default IngestJob createWithPayload(String source, String username, String visibility, boolean javaFile,
                                        String targetStatus, byte[] bytes) {
        return create(source, username, visibility);
    }

    Optional<IngestJob> find(UUID id);

    default Optional<JobInput> loadInput(UUID id) {
        return Optional.empty();
    }

    void updateStatus(UUID id, String status, Integer chunks, String error);

    default boolean claim(UUID id, String owner, long leaseSeconds) {
        return true;
    }

    default List<UUID> findRecoverable() {
        return List.of();
    }

    void deleteOld();
}
