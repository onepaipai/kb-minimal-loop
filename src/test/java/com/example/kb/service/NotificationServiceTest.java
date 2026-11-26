package com.example.kb.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 站内通知：待审批发给管理员、审批结果发给上传人；SQL 与收件人用 mock 的 JdbcTemplate 核对，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private NotificationService service() {
        return new NotificationService(jdbcTemplate);
    }

    @Test
    void pendingApprovalNotifiesAdminsOnlyForAllDocs() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of("admin"));
        service().notifyPendingApproval("a.txt", "all");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals("admin", args.getValue()[1]);
        assertEquals(NotificationService.TYPE_PENDING, args.getValue()[2]);
        assertEquals("a.txt", args.getValue()[5]);
    }

    @Test
    void pendingApprovalNotifiesDeptAdminForDeptDocs() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("admin", "tech-lead"));
        service().notifyPendingApproval("a.txt", "tech");
        // 两个收件人各发一条
        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void decisionNotifiesUploader() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("editor"));
        service().notifyDecision("a.txt", "admin", true);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals("editor", args.getValue()[1]);
        assertEquals(NotificationService.TYPE_APPROVED, args.getValue()[2]);
    }

    @Test
    void rejectionBodyContainsReason() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("editor"));
        service().notifyDecision("a.txt", "admin", false, "格式不符合规范");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertEquals(NotificationService.TYPE_REJECTED, args.getValue()[2]);
        assertTrue(String.valueOf(args.getValue()[4]).contains("格式不符合规范"));
    }

    @Test
    void decisionSkipsWhenActorIsUploaderOrUnknown() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("admin"));
        service().notifyDecision("a.txt", "admin", true);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        service().notifyDecision("a.txt", "admin", false);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }
}
