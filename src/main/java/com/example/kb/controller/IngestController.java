package com.example.kb.controller;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import com.example.kb.common.Visibility;
import com.example.kb.dto.IngestResponse;
import com.example.kb.service.AuditService;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.CodeIngestService;
import com.example.kb.service.DepartmentService;
import com.example.kb.service.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 文档入库接口：上传文件并切块入库；普通用户不能上传，入库后按角色定审批状态。 */
@RestController
@RequestMapping("/api")
public class IngestController {

    /** 支持上传的文件后缀白名单（不区分大小写） */
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".txt", ".pdf", ".doc", ".docx", ".md", ".java");
    private static final String JAVA_SUFFIX = ".java";

    private final IngestService ingestService;
    private final CodeIngestService codeIngestService;
    private final DepartmentService departmentService;
    private final AuditService auditService;

    public IngestController(IngestService ingestService, CodeIngestService codeIngestService,
                            DepartmentService departmentService, AuditService auditService) {
        this.ingestService = ingestService;
        this.codeIngestService = codeIngestService;
        this.departmentService = departmentService;
        this.auditService = auditService;
    }

    /** 上传文档并切块入库，支持 .txt/.pdf/.doc/.docx/.md/.java；visibility 可选，不传默认全员可见。 */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "visibility", required = false) String visibility) throws IOException {
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        DepartmentScope.requireCanUpload(user);
        String effectiveVisibility = visibility == null ? Visibility.ALL : Visibility.normalize(visibility);
        DepartmentScope.checkUploadVisibility(user, effectiveVisibility);
        departmentService.ensureDepartment(effectiveVisibility);
        String status = DepartmentScope.initialDocStatus(user, effectiveVisibility);
        String fileName = file.getOriginalFilename();
        String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (SUPPORTED_SUFFIXES.stream().noneMatch(lowerName::endsWith)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .txt/.pdf/.doc/.docx/.md/.java 文件");
        }
        IngestResponse response;
        if (lowerName.endsWith(JAVA_SUFFIX)) {
            // Java 单文件走代码知识库入库（JavaParser 按方法切块）
            response = codeIngestService.ingestJava(file, effectiveVisibility, status);
        } else {
            response = ingestService.ingest(file, effectiveVisibility, status);
        }
        auditService.record(user.username(), "UPLOAD", fileName,
                "visibility=" + effectiveVisibility + " status=" + status);
        return response;
    }
}
