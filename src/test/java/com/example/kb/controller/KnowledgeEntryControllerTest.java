package com.example.kb.controller;

import com.example.kb.dto.KnowledgeBatchDeleteRequest;
import com.example.kb.dto.KnowledgeEntryCreateRequest;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.KnowledgeEntryService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 知识条目接口：标题/正文必填校验、status 白名单、清单可见范围与删除；用 mock 的 Service，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeEntryControllerTest {

    @Mock
    private KnowledgeEntryService knowledgeEntryService;

    private KnowledgeEntryController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeEntryController(knowledgeEntryService);
        UserPrincipal user = new UserPrincipal("editor", "EDITOR", "hr");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsBlankTitle() {
        KnowledgeEntryCreateRequest request = new KnowledgeEntryCreateRequest();
        request.setContent("正文");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.create(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("标题"));
    }

    @Test
    void createRejectsBlankContent() {
        KnowledgeEntryCreateRequest request = new KnowledgeEntryCreateRequest();
        request.setTitle("标题");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.create(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("正文"));
    }

    @Test
    void createPassesRequestToService() {
        KnowledgeEntryCreateRequest request = new KnowledgeEntryCreateRequest();
        request.setTitle("项目报销指引");
        request.setContent("高铁二等座实报实销");
        request.setVisibility("all");
        KnowledgeEntryService.KnowledgeEntrySaved saved =
                new KnowledgeEntryService.KnowledgeEntrySaved(null, "项目报销指引", "项目报销指引.md", 3, "all", "pending");
        when(knowledgeEntryService.create(any(), eq("项目报销指引"), eq("高铁二等座实报实销"),
                isNull(), isNull(), eq("all"))).thenReturn(saved);

        KnowledgeEntryService.KnowledgeEntrySaved result = controller.create(request);

        assertEquals("项目报销指引.md", result.source());
        assertEquals("pending", result.status());
    }

    @Test
    void listRejectsUnknownStatus() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.list(null, "draft"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("status"));
    }

    @Test
    void listPassesCallerUsernameForNonAdmin() {
        when(knowledgeEntryService.list(anyString(), isNull(), anyBoolean(), eq("editor")))
                .thenReturn(List.of());

        controller.list(null, null);

        verify(knowledgeEntryService).list(anyString(), isNull(), anyBoolean(), eq("editor"));
    }

    @Test
    void batchDeleteReturnsDeletedIds() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(knowledgeEntryService.batchDelete(any(), any())).thenReturn(List.of(id));
        KnowledgeBatchDeleteRequest request = new KnowledgeBatchDeleteRequest();
        request.setIds(List.of(id));

        java.util.Map<String, Object> result = controller.batchDelete(request);

        assertEquals(1, result.get("requested"));
        assertEquals(List.of(String.valueOf(id)), result.get("deleted"));
    }

    @Test
    void batchDeleteRejectsEmptyIds() {
        KnowledgeBatchDeleteRequest request = new KnowledgeBatchDeleteRequest();
        request.setIds(List.of());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.batchDelete(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void deleteReturnsTrue() {
        java.util.Map<String, Object> result = controller.delete(java.util.UUID.randomUUID());

        assertEquals(true, result.get("deleted"));
    }
}
