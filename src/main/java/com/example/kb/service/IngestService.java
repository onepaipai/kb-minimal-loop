package com.example.kb.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.example.kb.dto.IngestResponse;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
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
 * 文档入库服务：txt 直读，PDF/DOC/DOCX/MD 走 Tika 解析，切块 → 向量化 → 存入 pgvector，同名文件整体替换。
 */
@Service
public class IngestService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentManageService documentManageService;
    private final ApacheTikaDocumentParser tikaDocumentParser = new ApacheTikaDocumentParser();

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
     * 按后缀分流解析入库：txt 直接按 UTF-8 读，其余交给 Tika 自动识别；同名文件先删旧块，重复上传即整体替换。
     */
    public IngestResponse ingest(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        Metadata metadata = new Metadata();
        metadata.put("source", fileName);
        Document document;
        if (fileName.endsWith(".txt")) {
            // txt 直接用已读的 UTF-8 文本，快且结果最准
            document = Document.from(content, metadata);
        } else {
            document = parseWithTika(file, metadata);
        }
        // 空内容校验放在解析之后：扫描版 PDF、纯空表格的 Word 解析不出文本，不允许静默存 0 块
        if (document.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件解析后无有效文本内容（可能为扫描版 PDF 或空文档，解析器不含 OCR）");
        }
        // 同名先删旧块，保证重复上传不产生重复块。
        // TODO 该顺序非原子：入库中途失败（向量接口报错、断库）旧块已丢失；接入正式数据前需改为先入后删或加事务/备份
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(fileName));
        // 切块→向量化→存库由 EmbeddingStoreIngestor 一次完成，块数以入库后实际落库统计为准
        embeddingStoreIngestor.ingest(document);
        IngestResponse response = new IngestResponse();
        response.setFile(fileName);
        response.setChunks(documentManageService.countBySource(fileName));
        return response;
    }

    /**
     * Tika 流式解析并重建 Document，手动补 source 元数据（Tika 不加，清单/删除/定向检索都依赖它）。
     */
    private Document parseWithTika(MultipartFile file, Metadata metadata) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Document parsed;
            try {
                parsed = tikaDocumentParser.parse(inputStream);
            } catch (BlankDocumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "文件解析后无有效文本内容（可能为扫描版 PDF 或纯图片文档，解析器不含 OCR）");
            } catch (RuntimeException e) {
                // Tika 对损坏/无法识别的文件统一包成 RuntimeException，按 400 处理
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件解析失败（格式损坏或无法识别）");
            }
            return Document.from(parsed.text(), metadata);
        }
    }
}
