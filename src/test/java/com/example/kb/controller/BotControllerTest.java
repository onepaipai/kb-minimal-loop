package com.example.kb.controller;

import com.example.kb.dto.AskResponse;
import com.example.kb.dto.BotAskRequest;
import com.example.kb.dto.BotAskResponse;
import com.example.kb.dto.BotReply;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.AuditService;
import com.example.kb.service.RagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 机器人问答：通用入参、钉钉/企微入参、空问题校验与审计记录；用 mock 的 Service，不碰真实库与模型。 */
@ExtendWith(MockitoExtension.class)
class BotControllerTest {

    @Mock
    private RagService ragService;

    @Mock
    private AuditService auditService;

    private BotController controller;

    @BeforeEach
    void setUp() {
        controller = new BotController(ragService, auditService);
        UserPrincipal user = new UserPrincipal("bot-user", "EDITOR", "hr");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void askWithPlainQuestionReturnsAnswerAndSources() {
        AskResponse response = new AskResponse();
        response.setAnswer("部门经理+HR 共同审批[1]");
        response.setSources(List.of("请假制度.txt"));
        when(ragService.ask(any(), any())).thenReturn(response);

        BotAskRequest request = new BotAskRequest();
        request.setQuestion("请假超过3天找谁批？");
        Object result = controller.ask(request);

        BotAskResponse body = assertInstanceOf(BotAskResponse.class, result);
        assertEquals("部门经理+HR 共同审批[1]", body.getAnswer());
        assertEquals(List.of("请假制度.txt"), body.getSources());
        verify(auditService).record(anyString(), anyString(), any(), anyString());
    }

    @Test
    void askWithWecomPlatformReturnsImReply() {
        AskResponse response = new AskResponse();
        response.setAnswer("答案[1]");
        response.setSources(List.of("请假制度.txt"));
        when(ragService.ask(any(), any())).thenReturn(response);

        BotAskRequest.TextPayload text = new BotAskRequest.TextPayload();
        text.setContent("请假超过3天找谁批？");
        BotAskRequest request = new BotAskRequest();
        request.setPlatform(" WeCom ");
        request.setText(text);

        Object result = controller.ask(request);

        BotReply reply = assertInstanceOf(BotReply.class, result);
        assertEquals("text", reply.getMsgtype());
        assertEquals("答案[1]", reply.getText().getContent());
    }

    @Test
    void askWithoutQuestionReturnsBadRequest() {
        BotAskRequest request = new BotAskRequest();
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.ask(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("question"));
    }
}
