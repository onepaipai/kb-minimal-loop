package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.example.kb.dto.IngestResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档入库服务：切块 → 向量化 → 存入 pgvector，同名文件整体替换。
 */
@Service
public class IngestService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentManageService documentManageService;

    public IngestService(DocumentSplitter documentSplitter, EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> embeddingStore, DocumentManageService documentManageService) {
        this.embeddingStore = embeddingStore;
        this.documentManageService = documentManageService;
        this.embeddingStoreIngestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(documentSplitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /**
     * 读取上传的文本文件，附带 source=文件名 元数据后入库；同名文件先删旧块，重复上传即整体替换。
     */
    public IngestResponse ingest(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        // 同名先删旧块，保证重复上传不产生重复块
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(fileName));
        Metadata metadata = new Metadata();
        metadata.put("source", fileName);
        Document document = Document.from(content, metadata);
        // 切块→向量化→存库由 EmbeddingStoreIngestor 一次完成，块数以入库后实际落库统计为准
        embeddingStoreIngestor.ingest(document);
        IngestResponse response = new IngestResponse();
        response.setFile(fileName);
        response.setChunks(documentManageService.countBySource(fileName));
        return response;
    }
}
