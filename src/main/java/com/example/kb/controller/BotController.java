package com.example.kb.controller;

import java.util.Locale;

import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.BotAskRequest;
import com.example.kb.dto.BotAskResponse;
import com.example.kb.dto.BotReply;
import com.example.kb.security.DepartmentScope;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.AuditService;
import com.example.kb.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 机器人问答接口：给企业微信/钉钉等 IM 机器人或外部系统对接，请求头 X-API-Key（ask 范围即可）。
 * 入参兼容两种：通用 {"question":"…"}，或钉钉/企微文本消息 {"msgtype":"text","text":{"content":"…"}}；
 * platform 传 dingtalk/wecom 时按 IM 回复格式 {"msgtype":"text","text":{"content":答案}} 返回。
 * IM 平台侧的回调地址、加解密与验签在企业 IM 管理后台配置后转发到本接口（企业侧配置，代码不含）。
 */
@RestController
@RequestMapping("/api/bot")
public class BotController {

    private static final String PLATFORM_DINGTALK = "dingtalk";
    private static final String PLATFORM_WECOM = "wecom";

    private final RagService ragService;
    private final AuditService auditService;

    public BotController(RagService ragService, AuditService auditService) {
        this.ragService = ragService;
        this.auditService = auditService;
    }

    /** 机器人问答：与 /api/ask 同一套检索、标准答案与盲区记录；部门权限按 Key 主人的账号规则算。 */
    @PostMapping("/ask")
    public Object ask(@RequestBody BotAskRequest request) {
        if (request.getPlatform() != null) {
            request.setPlatform(request.getPlatform().trim().toLowerCase(Locale.ROOT));
        }
        String question = resolveQuestion(request);
        if (question == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        UserPrincipal user = DepartmentScope.requireCurrentUser();
        RagService.AskScope scope = DepartmentScope.askScope(user, null);
        auditService.record(user.username(), "BOT_ASK", request.getPlatform(), question);
        AskRequest askRequest = new AskRequest();
        askRequest.setQuestion(question);
        AskResponse response = ragService.ask(askRequest, scope);
        if (PLATFORM_DINGTALK.equals(request.getPlatform()) || PLATFORM_WECOM.equals(request.getPlatform())) {
            return BotReply.ofText(response.getAnswer());
        }
        BotAskResponse body = new BotAskResponse();
        body.setAnswer(response.getAnswer());
        body.setSources(response.getSources());
        return body;
    }

    /** 从通用或钉钉/企微消息体里取问题文本：两种都没有时返回 null。 */
    private String resolveQuestion(BotAskRequest request) {
        if (request.getQuestion() != null && !request.getQuestion().isBlank()) {
            return request.getQuestion().trim();
        }
        if (request.getText() != null && request.getText().getContent() != null
                && !request.getText().getContent().isBlank()) {
            return request.getText().getContent().trim();
        }
        return null;
    }
}
