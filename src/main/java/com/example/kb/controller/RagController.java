package com.example.kb.controller;

import java.io.IOException;

import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.dto.IngestResponse;
import com.example.kb.service.DocumentManageService;
import com.example.kb.service.IngestService;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库接口：文档入库与检索问答。
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private final IngestService ingestService;
    private final RagService ragService;
    private final DocumentManageService documentManageService;

    public RagController(IngestService ingestService, RagService ragService,
                         DocumentManageService documentManageService) {
        this.ingestService = ingestService;
        this.ragService = ragService;
        this.documentManageService = documentManageService;
    }

    /**
     * 上传 .txt 文档并切块入库。
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam("file") MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".txt")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持上传 .txt 文件");
        }
        return ingestService.ingest(file);
    }

    /**
     * 基于知识库检索结果回答问题。
     */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return ragService.ask(request);
    }

    /**
     * 已入库文档清单：source 与块数。
     */
    @GetMapping("/documents")
    public DocumentListResponse documents() {
        return documentManageService.listDocuments();
    }

    /**
     * 删除指定文档的全部块。
     */
    @DeleteMapping("/document")
    public DeleteDocumentResponse deleteDocument(@RequestParam("source") String source) {
        return documentManageService.deleteDocument(source);
    }
}
