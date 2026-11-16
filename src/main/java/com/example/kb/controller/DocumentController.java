package com.example.kb.controller;

import com.example.kb.common.Visibility;
import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.security.DepartmentScope;
import com.example.kb.service.DocumentManageService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 已入库文档管理接口：清单与删除。 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentManageService documentManageService;

    public DocumentController(DocumentManageService documentManageService) {
        this.documentManageService = documentManageService;
    }

    /**
     * 已入库文档清单：source、块数与 visibility；department 可选，不传只列全员可见的。
     * 分页：page 从 0 起，size 1-100，默认 20；越界报 400。
     */
    @GetMapping("/documents")
    public DocumentListResponse documents(@RequestParam(value = "department", required = false) String department,
                                          @RequestParam(value = "page", defaultValue = "0") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 从 0 起，size 仅允许 1-100");
        }
        String requested = department == null ? null : Visibility.normalize(department);
        String effectiveDepartment = DepartmentScope.effectiveDepartment(DepartmentScope.currentUser(), requested);
        return documentManageService.listDocuments(effectiveDepartment, page, size);
    }

    /** 删除指定文档的全部块，仅管理员能调。 */
    @DeleteMapping("/document")
    @PreAuthorize("hasRole('ADMIN')")
    public DeleteDocumentResponse deleteDocument(@RequestParam("source") String source) {
        return documentManageService.deleteDocument(source);
    }
}
