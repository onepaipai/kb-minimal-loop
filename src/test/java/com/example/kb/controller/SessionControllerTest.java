package com.example.kb.controller;

import com.example.kb.security.UserPrincipal;
import com.example.kb.service.ChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话历史接口：清单、消息回放与删除都只按当前登录用户查，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private ChatSessionService chatSessionService;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionController(chatSessionService);
        UserPrincipal user = new UserPrincipal("alice", "USER", "hr");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listUsesCurrentUsername() {
        ChatSessionService.SessionItem item = new ChatSessionService.SessionItem("s1", "会话一", OffsetDateTime.now());
        when(chatSessionService.list("alice")).thenReturn(List.of(item));

        List<ChatSessionService.SessionItem> result = controller.list();

        assertEquals(1, result.size());
        assertEquals("s1", result.get(0).id());
        verify(chatSessionService).list("alice");
    }

    @Test
    void messagesUseCurrentUsername() {
        when(chatSessionService.messages("alice", "s1")).thenReturn(List.of());

        controller.messages("s1");

        verify(chatSessionService).messages("alice", "s1");
    }

    @Test
    void deleteUsesCurrentUsername() {
        when(chatSessionService.delete("alice", "s1")).thenReturn(true);

        java.util.Map<String, Object> result = controller.delete("s1");

        assertEquals(true, result.get("deleted"));
        verify(chatSessionService).delete(eq("alice"), eq("s1"));
    }
}
