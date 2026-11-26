package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.DocumentBatchDeleteRequest;
import com.example.kb.dto.DocumentBatchReviewRequest;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.DocumentBatchService;
import com.example.kb.service.DocumentManageService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档批量接口：入参去重、权限预检、非法参数校验；执行逻辑交给 DocumentBatchService。 */
@ExtendWith(MockitoExtension.class)
class DocumentBatchControllerTest {

    @Mock
    private DocumentBatchService documentBatchService;
    @Mock
    private DocumentManageService documentManageService;

    private DocumentBatchController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentBatchController(documentBatchService, documentManageService);
        UserPrincipal user = new UserPrincipal("admin", "ADMIN", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void batchDeleteDeduplicatesBeforeCallingService() {
        when(documentManageService.getVisibility(anyString())).thenReturn("all");
        when(documentBatchService.delete(any(), any())).thenReturn(
                java.util.Map.of("requested", 2, "deleted", List.of("a.txt", "b.txt"), "failed", List.of()));
        DocumentBatchDeleteRequest request = new DocumentBatchDeleteRequest();
        request.setSources(List.of("a.txt", "b.txt", "a.txt"));

        java.util.Map<String, Object> result = controller.batchDelete(request);

        assertEquals(2, result.get("requested"));
        verify(documentBatchService).delete(eq(List.of("a.txt", "b.txt")), any());
    }

    @Test
    void batchDeleteRejectsEmptySources() {
        DocumentBatchDeleteRequest request = new DocumentBatchDeleteRequest();
        request.setSources(List.of());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.batchDelete(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void batchReviewRejectsUnknownStatus() {
        DocumentBatchReviewRequest request = new DocumentBatchReviewRequest();
        request.setSources(List.of("a.txt"));
        request.setStatus("draft");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.batchReview(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void batchReviewPassesStatusAndReasonToService() {
        when(documentManageService.getVisibility(anyString())).thenReturn("all");
        when(documentBatchService.review(any(), anyString(), anyString(), any())).thenReturn(
                java.util.Map.of("requested", 1, "reviewed", List.of("a.txt"), "failed", List.of()));
        DocumentBatchReviewRequest request = new DocumentBatchReviewRequest();
        request.setSources(List.of("a.txt"));
        request.setStatus("rejected");
        request.setReason("格式不符");

        controller.batchReview(request);

        verify(documentBatchService).review(eq(List.of("a.txt")), eq("rejected"), eq("格式不符"), any());
    }
}
