package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.kb.dto.IngestResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档入库服务：切块 → 向量化 → 存入 pgvector。
 */
@Service
public class IngestService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final DocumentSplitter documentSplitter;

    public IngestService(DocumentSplitter documentSplitter, EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> embeddingStore) {
        this.documentSplitter = documentSplitter;
        this.embeddingStoreIngestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(documentSplitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /**
     * 读取上传的文本文件，附带 source=文件名 元数据后整体入库。
     */
    public IngestResponse ingest(MultipartFile file) throws IOException {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        Metadata metadata = new Metadata();
        metadata.put("source", file.getOriginalFilename());
        Document document = Document.from(content, metadata);
        // 预切一次仅用于统计块数，实际入库由 EmbeddingStoreIngestor 完成切块→向量化→存库
        List<TextSegment> segments = documentSplitter.split(document);
        embeddingStoreIngestor.ingest(document);
        IngestResponse response = new IngestResponse();
        response.setFile(file.getOriginalFilename());
        response.setChunks(segments.size());
        return response;
    }
}
