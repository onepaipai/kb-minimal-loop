package com.example.kb.controller;

import java.util.Set;

import com.example.kb.security.DepartmentScope;
import com.example.kb.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 管理侧数据导出：CSV / JSON / Markdown，四类数据集，仅管理员。 */
@RestController
@RequestMapping("/api/admin")
public class ExportController {

    private static final Set<String> DATASETS = Set.of("audit", "miss", "docs", "sessions");
    private static final Set<String> FORMATS = Set.of("csv", "json", "md");

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** 导出：dataset 四类、format 三种；CSV 带 UTF-8 BOM 便于 Excel 打开。 */
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> export(@RequestParam("dataset") String dataset,
                                         @RequestParam(value = "format", defaultValue = "csv") String format) {
        if (!DATASETS.contains(dataset)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataset 只允许 audit、miss、docs、sessions");
        }
        if (!FORMATS.contains(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format 只允许 csv、json、md");
        }
        String username = DepartmentScope.requireCurrentUser().username();
        byte[] body = exportService.export(dataset, format, username);
        if ("csv".equals(format)) {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] withBom = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, withBom, 0, bom.length);
            System.arraycopy(body, 0, withBom, bom.length, body.length);
            body = withBom;
        }
        String contentType = "json".equals(format) ? MediaType.APPLICATION_JSON_VALUE
                : "md".equals(format) ? "text/markdown; charset=UTF-8" : "text/csv; charset=UTF-8";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + dataset + "." + ("md".equals(format) ? "md" : format) + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(body);
    }
}
