package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** 异步入库提交：建一个 PENDING 任务并交给后台线程，立即返回任务号。 */
@ExtendWith(MockitoExtension.class)
class AsyncIngestServiceTest {

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
    IngestJobRunner jobRunner;

    @Test
    void submitCreatesPendingJobAndStartsRunner() {
        InMemoryStore store = new InMemoryStore();
        AsyncIngestService service = new AsyncIngestService(store, jobRunner);
        IngestJobStore.IngestJob job = service.submit("text".getBytes(StandardCharsets.UTF_8),
                "a.txt", false, "all", "approved", "alice");
        assertEquals("PENDING", job.status());
        assertEquals("a.txt", job.source());
        verify(jobRunner).run(eq(job.id()), any(), eq("a.txt"), eq(false), eq("all"), eq("approved"));
    }
}
