package com.example.kb.controller;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.example.kb.common.Visibility;
import com.example.kb.dto.IngestResponse;
import com.example.kb.service.AsyncIngestService;
import com.example.kb.service.AuditService;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.CodeIngestService;
import com.example.kb.service.DepartmentService;
import com.example.kb.service.IngestJobStore;
import com.example.kb.service.IngestService;
import com.example.kb.service.KnowledgeEntryService;
import com.example.kb.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 文档入库接口：上传文件并切块入库；普通用户不能上传，入库后按角色定审批状态。 */
@RestController
@RequestMapping("/api")
public class IngestController {

    /** 支持上传的文件后缀白名单（不区分大小写）：Office 文档走 Tika 解析 */
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".txt", ".pdf", ".doc", ".docx", ".md", ".java",
            ".xls", ".xlsx", ".ppt", ".pptx");
    private static final String JAVA_SUFFIX = ".java";

    private final IngestService ingestService;
    private final CodeIngestService codeIngestService;
    private final DepartmentService departmentService;
    private final AuditService auditService;
    private final AsyncIngestService asyncIngestService;
    private final IngestJobStore jobStore;
    private final NotificationService notificationService;
    private final KnowledgeEntryService knowledgeEntryService;

    public IngestController(IngestService ingestService, CodeIngestService codeIngestService,
                            DepartmentService departmentService, AuditService auditService,
                            AsyncIngestService asyncIngestService, IngestJobStore jobStore,
                            NotificationService notificationService, KnowledgeEntryService knowledgeEntryService) {
        this.ingestService = ingestService;
        this.codeIngestService = codeIngestService;
        this.departmentService = departmentService;
        this.auditService = auditService;
        this.asyncIngestService = asyncIngestService;
        this.jobStore = jobStore;
        this.notificationService = notificationService;
        this.knowledgeEntryService = knowledgeEntryService;
    }

    /** 上传文档并切块入库，支持 .txt/.pdf/.doc/.docx/.md/.java/.xls/.xlsx/.ppt/.pptx；visibility 可选，不传默认全员可见。 */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "visibility", required = false) String visibility) throws IOException {
        UploadPrep prep = prepareUpload(file, visibility);
        IngestResponse response;
        if (prep.lowerName().endsWith(JAVA_SUFFIX)) {
            // Java 单文件走代码知识库入库（JavaParser 按方法切块）
            response = codeIngestService.ingestJava(file, prep.visibility(), prep.status());
        } else {
            response = ingestService.ingest(file, prep.visibility(), prep.status());
        }
        auditService.record(prep.user().username(), "UPLOAD", prep.fileName(),
                "visibility=" + prep.visibility() + " status=" + prep.status());
        notifyIfPending(prep);
        return response;
    }

    /** 异步上传：立即返回 202 与任务号，解析与向量化在后台做，前端轮询 /api/ingest/jobs/{jobId} 看进度。 */
    @PostMapping("/ingest-async")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestJobStore.IngestJob ingestAsync(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "visibility", required = false) String visibility)
            throws IOException {
        UploadPrep prep = prepareUpload(file, visibility);
        IngestJobStore.IngestJob job = asyncIngestService.submit(file.getBytes(), prep.fileName(),
                prep.lowerName().endsWith(JAVA_SUFFIX), prep.visibility(), prep.status(), prep.user().username());
        auditService.record(prep.user().username(), "UPLOAD", prep.fileName(),
                "async jobId=" + job.id() + " visibility=" + prep.visibility() + " status=" + prep.status());
        notifyIfPending(prep);
        return job;
    }

    /** 查异步入库任务：只能看自己的任务，管理员看全部；别人的任务按不存在处理。 */
    @GetMapping("/ingest/jobs/{jobId}")
    public IngestJobStore.IngestJob getJob(@PathVariable UUID jobId) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        IngestJobStore.IngestJob job = jobStore.find(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        if (!user.isAdmin() && !job.username().equals(user.username())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        return job;
    }

    /** 待审批的上传给管理员发提醒；直接发布的（status=approved）不发。 */
    private void notifyIfPending(UploadPrep prep) {
        if ("pending".equals(prep.status())) {
            notificationService.notifyPendingApproval(prep.fileName(), prep.visibility());
        }
    }

    /** 上传前的公共校验：权限、可见性、部门登记、审批初始状态、后缀白名单。 */
    private UploadPrep prepareUpload(MultipartFile file, String visibility) {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        DepartmentScope.requireCanUpload(user);
        String effectiveVisibility = visibility == null ? Visibility.ALL : Visibility.normalize(visibility);
        DepartmentScope.checkUploadVisibility(user, effectiveVisibility);
        departmentService.ensureDepartment(effectiveVisibility);
        String status = DepartmentScope.initialDocStatus(user, effectiveVisibility);
        String fileName = file.getOriginalFilename();
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (SUPPORTED_SUFFIXES.stream().noneMatch(lowerName::endsWith)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅支持 .txt/.pdf/.doc/.docx/.md/.java/.xls/.xlsx/.ppt/.pptx 文件");
        }
        if (knowledgeEntryService.existsBySource(fileName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "同名知识条目已存在，同名替换会顶掉它的检索块；请改文件名或先删除该条目");
        }
        return new UploadPrep(user, fileName, lowerName, effectiveVisibility, status);
    }

    private record UploadPrep(UserPrincipal user, String fileName, String lowerName, String visibility, String status) {
    }
}
