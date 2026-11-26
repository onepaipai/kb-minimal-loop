package com.example.kb.service;

import java.util.List;
import java.util.Map;

import com.example.kb.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单条/批量审批的公共执行层：驳回理由进审计与通知，条目行状态同步。 */
@ExtendWith(MockitoExtension.class)
class DocumentBatchServiceTest {

    @Mock
    private DocumentManageService documentManageService;
    @Mock
    private KnowledgeEntryService knowledgeEntryService;
    @Mock
    private AuditService auditService;
    @Mock
    private NotificationService notificationService;

    private DocumentBatchService service() {
        return new DocumentBatchService(documentManageService, knowledgeEntryService,
                auditService, notificationService);
    }

    @Test
    void reviewOneRejectWritesReasonToAuditAndNotification() {
        when(documentManageService.setStatus("a.txt", "rejected")).thenReturn(3);
        UserPrincipal user = new UserPrincipal("admin", "ADMIN", null);

        Map<String, Object> result = service().reviewOne("a.txt", "rejected", "格式不符", user);

        assertEquals(3, result.get("chunks"));
        verify(knowledgeEntryService).syncStatusBySource("a.txt", "rejected");
        verify(auditService).record(eq("admin"), eq("REJECT"), eq("a.txt"),
                eq("chunks=3,reason=格式不符"));
        verify(notificationService).notifyDecision("a.txt", "admin", false, "格式不符");
    }

    @Test
    void reviewContinuesAfterOneFailure() {
        when(documentManageService.setStatus("a.txt", "approved")).thenThrow(new RuntimeException("库异常"));
        when(documentManageService.setStatus("b.txt", "approved")).thenReturn(1);
        UserPrincipal user = new UserPrincipal("admin", "ADMIN", null);

        Map<String, Object> result = service().review(List.of("a.txt", "b.txt"), "approved", null, user);

        assertEquals(2, result.get("requested"));
        assertEquals(List.of("b.txt"), result.get("reviewed"));
        assertEquals(1, ((List<?>) result.get("failed")).size());
    }
}
