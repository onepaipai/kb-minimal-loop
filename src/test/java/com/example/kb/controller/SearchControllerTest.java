package com.example.kb.controller;

import java.util.List;

import com.example.kb.dto.AskRequest;
import com.example.kb.dto.ReferenceHit;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 仅检索接口：入参校验、返回命中块与审计记录；用 mock 的 RagService，不碰真实库与模型。 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private RagService ragService;

    @Mock
    private AuditService auditService;

    private SearchController controller;

    @BeforeEach
    void setUp() {
        controller = new SearchController(ragService, auditService);
        UserPrincipal user = new UserPrincipal("alice", "USER", "hr");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void searchReturnsHits() {
        ReferenceHit hit = new ReferenceHit();
        hit.setIndex(1);
        hit.setScore(0.8);
        hit.setSource("请假制度.txt");
        hit.setText("部门经理+HR 共同审批");
        when(ragService.searchHits(any(), any())).thenReturn(List.of(hit));
        AskRequest request = new AskRequest();
        request.setQuestion("请假超过3天找谁批？");

        List<ReferenceHit> hits = controller.search(request);

        assertEquals(1, hits.size());
        assertEquals("请假制度.txt", hits.get(0).getSource());
        verify(auditService).record(any(), any(), any(), any());
    }

    @Test
    void searchRejectsBlankQuestion() {
        AskRequest request = new AskRequest();
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.search(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void searchRejectsBadMaxResults() {
        AskRequest request = new AskRequest();
        request.setQuestion("问题");
        request.setMaxResults(21);
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.search(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
