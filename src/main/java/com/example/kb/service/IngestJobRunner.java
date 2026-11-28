package com.example.kb.service;

import java.util.UUID;

import com.example.kb.dto.IngestResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 异步入库执行器：任务输入持久化，领取使用租约，失败由任务状态和批次补偿共同收敛。 */
@Service
public class IngestJobRunner {

    private static final long LEASE_SECONDS = 180;

    private final IngestJobStore jobStore;
    private final IngestService ingestService;
    private final CodeIngestService codeIngestService;
    private final NotificationService notificationService;
    private final String workerId = UUID.randomUUID().toString();

    @Autowired
    public IngestJobRunner(IngestJobStore jobStore, IngestService ingestService, CodeIngestService codeIngestService,
                           NotificationService notificationService) {
        this.jobStore = jobStore;
        this.ingestService = ingestService;
        this.codeIngestService = codeIngestService;
        this.notificationService = notificationService;
    }

    IngestJobRunner(IngestJobStore jobStore, IngestService ingestService, CodeIngestService codeIngestService) {
        this.jobStore = jobStore;
        this.ingestService = ingestService;
        this.codeIngestService = codeIngestService;
        this.notificationService = null;
    }

    /** 新任务和恢复任务都只传 jobId，后台从数据库读取输入，进程重启后仍可接管。 */
    @Async("ingestExecutor")
    public void run(UUID jobId) {
        IngestJobStore.JobInput input = jobStore.loadInput(jobId).orElse(null);
        if (input == null) {
            jobStore.updateStatus(jobId, "FAILED", null, "任务输入不存在，无法恢复");
            return;
        }
        if (!jobStore.claim(jobId, workerId, LEASE_SECONDS)) {
            return;
        }
        execute(jobId, input.bytes(), input.source(), input.javaFile(), input.visibility(), input.targetStatus());
    }

    /** 保留给现有同步单元测试和内部调用的执行入口；生产任务走上面的持久化入口。 */
    public void run(UUID jobId, byte[] bytes, String fileName, boolean isJava,
                    String visibility, String status) {
        execute(jobId, bytes, fileName, isJava, visibility, status);
    }

    /** 定期接管队列中的 PENDING 和租约已过期的 RUNNING 任务。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void recoverPendingJobs() {
        for (UUID jobId : jobStore.findRecoverable()) {
            run(jobId);
        }
    }

    private void execute(UUID jobId, byte[] bytes, String fileName, boolean isJava,
                         String visibility, String status) {
        try {
            IngestResponse response = isJava
                    ? codeIngestService.ingestJavaBytes(bytes, fileName, visibility, status)
                    : ingestService.ingestBytes(bytes, fileName, visibility, status);
            jobStore.updateStatus(jobId, "DONE", response.getChunks(), null);
            if ("pending".equals(status) && notificationService != null) {
                notificationService.notifyPendingApproval(fileName, visibility);
            }
        } catch (ResponseStatusException e) {
            jobStore.updateStatus(jobId, "FAILED", null, e.getReason());
        } catch (Exception e) {
            jobStore.updateStatus(jobId, "FAILED", null, "入库失败：" + e.getMessage());
        }
    }
}
