package com.example.kb.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档入库服务：txt/md 直接读 UTF-8（保留 Markdown 结构供切块器按标题切），PDF/DOC/DOCX 走 Tika 解析，
 * 切块 → 向量化 → 存入 pgvector，同名文件整体替换。
 */
@Service
public class IngestService {

    private static final String TEXT_SUFFIX = ".txt";
    private static final String MARKDOWN_SUFFIX = ".md";

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
     * 按后缀分流解析入库：txt/md 直接按 UTF-8 读（md 走 Tika 会丢标题结构），其余交给 Tika 自动识别；
     * 同名文件先入后删整体替换。
     * status 为审批初始状态（管理员与本部门管理员直接发布，其余待审批），version 为同名替换的下一个版本号。
     */
    public IngestResponse ingest(MultipartFile file, String visibility, String status) throws IOException {
        return ingestBytes(file.getBytes(), file.getOriginalFilename(), visibility, status);
    }

    /** 同 ingest，但入参是已读进内存的字节数组，供异步入库在后台线程调用。 */
    public IngestResponse ingestBytes(byte[] bytes, String fileName, String visibility, String status) throws IOException {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        // 内容没变直接跳过：不重新向量化、不重复存块（哈希按原始字节算，换行符变了也算变了）
        String fileHash = sha256Hex(bytes);
        if (fileHash.equals(documentManageService.findFileHash(fileName))) {
            return IngestResponse.skipped(fileName, documentManageService.countBySource(fileName),
                    visibility, status);
        }
        int version = documentManageService.nextVersion(fileName);
        Metadata metadata = new Metadata();
        metadata.put(MetadataKeys.SOURCE, fileName);
        metadata.put(MetadataKeys.FILE_HASH, fileHash);
        metadata.put(MetadataKeys.VISIBILITY, visibility);
        metadata.put(MetadataKeys.BATCH, UUID.randomUUID().toString());
        // 先以 pending 入库（问答查不到）：入库中途断了旧版还活着，发布那一步再一个事务里改成目标状态并 supersede 旧版
        metadata.put(MetadataKeys.STATUS, "pending");
        metadata.put(MetadataKeys.VERSION, String.valueOf(version));
        Document document;
        if (fileName.endsWith(TEXT_SUFFIX) || fileName.endsWith(MARKDOWN_SUFFIX)) {
            document = Document.from(content, metadata);
        } else {
            document = parseWithTika(bytes, metadata);
        }
        // 空内容校验放在解析之后：扫描版 PDF、纯空表格的 Word 解析不出文本，不允许静默存 0 块；
        // 扫描版 PDF 要读出文字需机器装 Tesseract（OCR 由 Tika 调它做，没装时这里就是空）
        if (document.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文件解析后无有效文本内容（可能是扫描版 PDF：服务器需安装 Tesseract 才支持 OCR，或为空文档）");
        }
        // 先入后发布：新块入库成功才一个事务里改成目标状态并把旧版标成 superseded（不删，留着回滚），
        // 入库中途失败（向量接口报错、断库）旧版不动，新版 pending 不可见
        embeddingStoreIngestor.ingest(document);
        documentManageService.publishVersion(fileName, metadata.getString(MetadataKeys.BATCH), status);
        return IngestResponse.of(fileName, documentManageService.countBySource(fileName), visibility, status);
    }

    /** 整个文件内容的 SHA-256（十六进制小写）：同名同内容再次上传时直接跳过。 */
    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * Tika 流式解析并重建 Document，手动补 source/visibility 元数据（Tika 不加，清单/删除/定向检索与权限过滤都依赖它）。
     */
    private Document parseWithTika(byte[] bytes, Metadata metadata) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            Document parsed;
            try {
                parsed = tikaDocumentParser.parse(inputStream);
            } catch (BlankDocumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "文件解析后无有效文本内容（可能是扫描版 PDF：服务器需安装 Tesseract 才支持 OCR，或为纯图片文档）");
            } catch (RuntimeException e) {
                // Tika 对损坏/无法识别的文件统一包成 RuntimeException，按 400 处理
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件解析失败（格式损坏或无法识别）");
            }
            return Document.from(parsed.text(), metadata);
        }
    }
}
