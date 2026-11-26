package com.example.kb.controller;

import com.example.kb.dto.FaqCreateRequest;
import com.example.kb.dto.FaqUpdateRequest;
import com.example.kb.security.UserPrincipal;
import com.example.kb.service.AuditService;
import com.example.kb.service.FaqService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 标准答案接口：入参校验、部门管理员越权拦截、清单可见范围；用 mock 的 Service，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class FaqControllerTest {

    @Mock
    private FaqService faqService;

    @Mock
    private AuditService auditService;

    private FaqController controller;

    private static FaqService.FaqEntry entry(String department) {
        return new FaqService.FaqEntry(UUID.randomUUID(), "问题", "答案", null, department,
                true, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static void login(UserPrincipal user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createRejectsBlankQuestion() {
        controller = new FaqController(faqService, auditService);
        login(new UserPrincipal("admin", "ADMIN", null));
        FaqCreateRequest request = new FaqCreateRequest();
        request.setAnswer("答案");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.create(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("question"));
    }

    @Test
    void createForcesDepartmentForDeptAdmin() {
        controller = new FaqController(faqService, auditService);
        login(new UserPrincipal("dept-admin", "DEPT_ADMIN", "hr"));
        FaqCreateRequest request = new FaqCreateRequest();
        request.setQuestion("问题");
        request.setAnswer("答案");
        request.setDepartment("finance");
        when(faqService.create("问题", "答案", null, "hr", "dept-admin")).thenReturn(entry("hr"));

        FaqService.FaqEntry created = controller.create(request);

        assertEquals("hr", created.department());
        verify(auditService).record(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void listOnlyShowsOwnDepartmentForDeptAdmin() {
        controller = new FaqController(faqService, auditService);
        login(new UserPrincipal("dept-admin", "DEPT_ADMIN", "hr"));
        when(faqService.list("hr")).thenReturn(List.of(entry("hr")));

        List<FaqService.FaqEntry> list = controller.list();

        assertEquals(1, list.size());
        assertEquals("hr", list.get(0).department());
    }

    @Test
    void updateRejectsOtherDepartmentEntry() {
        controller = new FaqController(faqService, auditService);
        login(new UserPrincipal("dept-admin", "DEPT_ADMIN", "hr"));
        UUID id = UUID.randomUUID();
        when(faqService.get(id)).thenReturn(entry("finance"));

        FaqUpdateRequest request = new FaqUpdateRequest();
        request.setAnswer("新答案");
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.update(id, request));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void deleteRejectsOtherDepartmentEntry() {
        controller = new FaqController(faqService, auditService);
        login(new UserPrincipal("dept-admin", "DEPT_ADMIN", "hr"));
        UUID id = UUID.randomUUID();
        when(faqService.get(id)).thenReturn(entry("finance"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.delete(id));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
