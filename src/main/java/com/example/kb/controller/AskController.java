package com.example.kb.controller;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.example.kb.common.Visibility;
import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.ReferenceHit;
import com.example.kb.security.DepartmentScope;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 检索问答接口：整段回答与流式回答。 */
@RestController
@RequestMapping("/api")
public class AskController {

    /** SSE 超时 120 秒，长回答不被默认 30 秒掐断。 */
    private static final long SSE_TIMEOUT_MILLIS = 120_000L;
    private static final String EVENT_SOURCES = "sources";
    private static final String EVENT_TOKEN = "token";
    private static final String EVENT_DONE = "done";

    private final RagService ragService;

    public AskController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 基于知识库检索结果回答问题。入参越界一律 400。 */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        validateAskRequest(request);
        return ragService.ask(request, DepartmentScope.askScope(DepartmentScope.currentUser(), request.getDepartment()));
    }

    /**
     * 流式问答：与 /api/ask 同一套检索与校验，答案逐 token 以 SSE 推送。
     * 事件顺序：sources（引用来源）→ token（逐个）→ done；出错时 completeWithError。
     * 流式调用跑在独立线程，接口立即返回 emitter；作用域在请求线程里算好传进去。
     */
    @PostMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody AskRequest request) {
        validateAskRequest(request);
        RagService.AskScope scope = DepartmentScope.askScope(DepartmentScope.currentUser(), request.getDepartment());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        CompletableFuture.runAsync(() -> streamTo(request, scope, emitter));
        return emitter;
    }

    private void streamTo(AskRequest request, RagService.AskScope scope, SseEmitter emitter) {
        try {
            ragService.askStream(request, scope, sseListener(emitter));
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

    /** /api/ask 与 /api/ask-stream 共用的入参校验，越界一律 400；部门代号在入口统一小写并校验，prepare() 直接用。 */
    private void validateAskRequest(AskRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        if (request.getDepartment() != null) {
            request.setDepartment(Visibility.normalize(request.getDepartment()));
        }
        if (request.getMaxResults() != null && (request.getMaxResults() < 1 || request.getMaxResults() > 20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxResults 仅允许 1-20");
        }
        if (request.getMinScore() != null && (request.getMinScore() < 0 || request.getMinScore() > 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minScore 仅允许 0-1");
        }
    }
}
