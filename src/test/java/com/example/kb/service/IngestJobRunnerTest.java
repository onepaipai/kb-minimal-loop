package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.example.kb.dto.IngestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 异步入库后台执行：成功记块数、失败记原因、.java 走代码入库；入库服务用 Mockito 假的，不碰数据库与向量接口。 */
@ExtendWith(MockitoExtension.class)
class IngestJobRunnerTest {

    static class InMemoryStore implements IngestJobStore {
        final Map<UUID, IngestJob> jobs = new HashMap<>();

        @Override
        public IngestJob create(String source, String username, String visibility) {
            IngestJob job = new IngestJob(UUID.randomUUID(), source, username, visibility, "PENDING", 0, null, null);
            jobs.put(job.id(), job);
            return job;
        }

        @Override
        public Optional<IngestJob> find(UUID id) {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public void updateStatus(UUID id, String status, Integer chunks, String error) {
            jobs.computeIfPresent(id, (k, v) -> new IngestJob(v.id(), v.source(), v.username(), v.visibility(),
                    status, chunks == null ? v.chunks() : chunks, error, v.createdAt()));
        }

        @Override
        public void deleteOld() {
        }
    }

    @Mock
    IngestService ingestService;
    @Mock
    CodeIngestService codeIngestService;

    InMemoryStore store = new InMemoryStore();
    IngestJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new IngestJobRunner(store, ingestService, codeIngestService);
    }

    @Test
    void txtSuccessMarksDoneWithChunks() throws Exception {
        IngestJobStore.IngestJob job = store.create("a.txt", "alice", "all");
        when(ingestService.ingestBytes(any(), eq("a.txt"), eq("all"), eq("approved")))
                .thenReturn(IngestResponse.of("a.txt", 3, "all", "approved"));
        runner.run(job.id(), "text".getBytes(StandardCharsets.UTF_8), "a.txt", false, "all", "approved");
        assertEquals("DONE", store.find(job.id()).orElseThrow().status());
        assertEquals(3, store.find(job.id()).orElseThrow().chunks());
    }

    @Test
    void javaGoesToCodeService() throws Exception {
        IngestJobStore.IngestJob job = store.create("A.java", "alice", "all");
        when(codeIngestService.ingestJavaBytes(any(), eq("A.java"), eq("all"), eq("approved")))
                .thenReturn(IngestResponse.of("A.java", 2, "all", "approved"));
        runner.run(job.id(), "class A {}".getBytes(StandardCharsets.UTF_8), "A.java", true, "all", "approved");
        verify(codeIngestService).ingestJavaBytes(any(), eq("A.java"), eq("all"), eq("approved"));
        assertEquals("DONE", store.find(job.id()).orElseThrow().status());
    }

    @Test
    void validationFailureMarksFailedWithReason() throws Exception {
        IngestJobStore.IngestJob job = store.create("a.txt", "alice", "all");
        when(ingestService.ingestBytes(any(), eq("a.txt"), eq("all"), eq("approved")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空"));
        runner.run(job.id(), new byte[0], "a.txt", false, "all", "approved");
        IngestJobStore.IngestJob done = store.find(job.id()).orElseThrow();
        assertEquals("FAILED", done.status());
        assertEquals("文件内容为空", done.error());
    }
}
