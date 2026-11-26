package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.DeleteDocumentResponse;
import com.example.kb.dto.DocumentBatchDeleteRequest;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.AuditService;
import com.example.kb.service.DocumentBlobService;
import com.example.kb.service.DocumentManageService;
import com.example.kb.service.KnowledgeEntryService;
import com.example.kb.service.NotificationService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档批量删除：去重、逐条删除与审计、空列表校验；用 mock 的 Service，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class DocumentControllerTest {

    @Mock
    private DocumentManageService documentManageService;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private KnowledgeEntryService knowledgeEntryService;
    @Mock
    private DocumentBlobService documentBlobService;

    private DocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentManageService, auditService, notificationService,
                knowledgeEntryService, documentBlobService);
        UserPrincipal user = new UserPrincipal("admin", "ADMIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void batchDeleteDeduplicatesAndDeletes() {
        when(documentManageService.getVisibility(anyString())).thenReturn("all");
        DeleteDocumentResponse response = new DeleteDocumentResponse();
        response.setDeleted(true);
        response.setDeletedChunks(3);
        when(documentManageService.deleteDocument(anyString())).thenReturn(response);
        DocumentBatchDeleteRequest request = new DocumentBatchDeleteRequest();
        request.setSources(List.of("a.txt", "b.txt", "a.txt"));

        java.util.Map<String, Object> result = controller.batchDelete(request);

        assertEquals(2, result.get("requested"));
        assertEquals(List.of("a.txt", "b.txt"), result.get("deleted"));
        verify(documentManageService, times(2)).deleteDocument(anyString());
        verify(auditService, times(2)).record(anyString(), eq("DELETE_DOC"), anyString(), anyString());
    }

    @Test
    void batchDeleteRejectsEmptySources() {
        DocumentBatchDeleteRequest request = new DocumentBatchDeleteRequest();
        request.setSources(List.of());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.batchDelete(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
