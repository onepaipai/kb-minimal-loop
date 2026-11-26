package com.example.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 会话两阶段落库：提问即写 user 消息，回答完成后补 assistant 消息；空 sessionId 不落库。 */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void appendQuestionWritesSessionAndUserMessage() {
        ChatSessionService service = new ChatSessionService(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());

        service.appendQuestion("alice", " s1 ", "hr", "请假超过3天找谁批？");

        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void completeAnswerWritesAssistantMessage() {
        ChatSessionService service = new ChatSessionService(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());

        service.completeAnswer("alice", "s1", "部门经理+HR 共同审批", null);

        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void emptySessionIdSkipsWrites() {
        ChatSessionService service = new ChatSessionService(jdbcTemplate, new com.fasterxml.jackson.databind.ObjectMapper());

        service.appendQuestion("alice", null, "hr", "问题");
        service.completeAnswer("alice", "  ", "答案", null);

        verifyNoInteractions(jdbcTemplate);
    }
}
