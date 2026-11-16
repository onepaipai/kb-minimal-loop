package com.example.kb.controller;

import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.service.DocumentManageService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 已入库文档管理接口：清单与删除。 */
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentManageService documentManageService;

    public DocumentController(DocumentManageService documentManageService) {
        this.documentManageService = documentManageService;
    }

    /** 已入库文档清单：source 与块数。 */
    @GetMapping("/documents")
    public DocumentListResponse documents() {
        return documentManageService.listDocuments();
    }

    /** 删除指定文档的全部块。 */
    @DeleteMapping("/document")
    public DeleteDocumentResponse deleteDocument(@RequestParam("source") String source) {
        return documentManageService.deleteDocument(source);
    }
}
