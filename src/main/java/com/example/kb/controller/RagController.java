package com.example.kb.controller;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentListResponse;
import com.example.kb.dto.IngestResponse;
import com.example.kb.dto.ReferenceHit;
import com.example.kb.service.CodeIngestService;
import com.example.kb.service.DocumentManageService;
import com.example.kb.service.IngestService;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库接口：文档入库与检索问答。
 */
@RestController
@RequestMapping("/api")
public class RagController {

    /** 支持上传的文件后缀白名单（不区分大小写） */
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(".txt", ".pdf", ".doc", ".docx", ".md", ".java");
    private static final String JAVA_SUFFIX = ".java";

    /** SSE 超时 120 秒，长回答不被默认 30 秒掐断。 */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_TOKEN = "token";
    private static final String EVENT_DONE = "done";

    private final IngestService ingestService;
    private final RagService ragService;
    private final DocumentManageService documentManageService;
    private final CodeIngestService codeIngestService;

    public RagController(IngestService ingestService, RagService ragService,
                         DocumentManageService documentManageService, CodeIngestService codeIngestService) {
        this.ingestService = ingestService;
        this.ragService = ragService;
        this.documentManageService = documentManageService;
        this.codeIngestService = codeIngestService;
    }

    /**
     * 上传文档并切块入库，支持 .txt/.pdf/.doc/.docx/.md/.java。
     */
    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestParam("file") MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String lowerName = "";
        if (fileName != null) {
            lowerName = fileName.toLowerCase(Locale.ROOT);
        }
        boolean supported = false;
        for (String suffix : SUPPORTED_SUFFIXES) {
            if (lowerName.endsWith(suffix)) {
                supported = true;
            }
        }
        if (!supported) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 .txt/.pdf/.doc/.docx/.md/.java 文件");
        }
        if (lowerName.endsWith(JAVA_SUFFIX)) {
            // Java 单文件走代码知识库入库（JavaParser 按方法切块）
            return codeIngestService.ingestJava(file);
        }
        return ingestService.ingest(file);
    }

    /**
     * 基于知识库检索结果回答问题。入参越界一律 400。
     */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        validateAskRequest(request);
        return ragService.ask(request);
    }

    /**
     * 流式问答：与 /api/ask 同一套检索与校验，答案逐 token 以 SSE 推送。
     * 事件顺序：sources（引用来源）→ token（逐个）→ done；出错时 completeWithError。
     * 流式调用跑在独立线程，接口立即返回 emitter。
     */
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskRequest request) {
        validateAskRequest(request);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        CompletableFuture.runAsync(() -> streamTo(request, emitter));
        return emitter;
    }

    private void streamTo(AskRequest request, SseEmitter emitter) {
        try {
            ragService.askStream(request, sseListener(emitter));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private RagService.StreamListener sseListener(SseEmitter emitter) {
        return new RagService.StreamListener() {
            @Override
            public void onSources(List<ReferenceHit> hits) {
                sendEvent(emitter, EVENT_SOURCES, hits);
            }

            @Override
            public void onToken(String token) {
                sendEvent(emitter, EVENT_TOKEN, token);
            }

            @Override
            public void onComplete() {
                if (sendEvent(emitter, EVENT_DONE, EVENT_DONE)) {
                    emitter.complete();
                }
            }

            @Override
            public void onError(Throwable error) {
                emitter.completeWithError(error);
            }
        };
    }

    private static boolean sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            return true;
        } catch (IOException e) {
            emitter.completeWithError(e);
            return false;
        }
    }

    /** /api/ask 与 /api/ask-stream 共用的入参校验，越界一律 400。 */
    private void validateAskRequest(AskRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        if (request.getMaxResults() != null && (request.getMaxResults() < 1 || request.getMaxResults() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxResults 仅允许 1-20");
        }
        if (request.getMinScore() != null && (request.getMinScore() < 0 || request.getMinScore() > 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minScore 仅允许 0-1");
        }
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
