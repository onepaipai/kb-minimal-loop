package com.example.kb.service;

import java.util.List;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 版本回滚：旧版标 superseded 不删、审批只动当前版、回滚恢复旧状态；SQL 用 mock 的 JdbcTemplate 核对，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class DocumentManageServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private DocumentBlobService documentBlobService;

    private DocumentManageService service() {
        return new DocumentManageService(jdbcTemplate, embeddingStore, documentBlobService);
    }

    @Test
    void markSupersededKeepsOldVersionAndRecordsPrevStatus() {
        service().markSuperseded("a.txt", "batch-1");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("a.txt"), eq("batch-1"));
        assertTrue(sql.getValue().contains("superseded"));
        assertTrue(sql.getValue().contains("prev_status"));
        assertTrue(sql.getValue().contains("COALESCE(metadata->>'batch', '') <> ?"));
    }

    @Test
    void setStatusOnlyTouchesCurrentVersion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(2);
        service().setStatus("a.txt", "approved");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("approved"), eq("a.txt"), eq(2));
        assertTrue(sql.getValue().contains("CAST(NULLIF(metadata->>'version','') AS INTEGER) = ?"));
    }

    @Test
    void rollbackRestoresPrevStatusAndSupersedesCurrent() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(3, 5);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3, 5);
        int restored = service().rollback("a.txt", 2);
        assertEquals(5, restored);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), any(Object[].class));
        List<String> sqls = sql.getAllValues();
        assertTrue(sqls.get(0).contains("superseded"));
        assertTrue(sqls.get(1).contains("prev_status"));
    }

    @Test
    void rollbackRejectsCurrentOrNewerVersion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(3);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().rollback("a.txt", 3));
        assertTrue(error.getStatusCode().is4xxClientError());
    }

    @Test
    void rollbackRejectsMissingTargetVersion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(3, 0);
        assertThrows(ResponseStatusException.class, () -> service().rollback("a.txt", 2));
    }

    @Test
    void publishVersionSetsTargetStatusThenSupersedesOld() {
        service().publishVersion("a.txt", "batch-1", "approved");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), args.capture());
        List<String> sqls = sql.getAllValues();
        // 第一条：新批次改成目标状态；第二条：其余旧版标 superseded
        assertTrue(sqls.get(0).contains("COALESCE(metadata->>'batch', '') = ?"));
        assertEquals("approved", args.getAllValues().get(0)[0]);
        assertTrue(sqls.get(1).contains("superseded"));
        assertTrue(sqls.get(1).contains("prev_status"));
    }

    @Test
    void purgeOldSupersededKeepsLatestVersions() {
        service().purgeOldSuperseded(3);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(3));
        assertTrue(sql.getValue().contains("DELETE FROM"));
        assertTrue(sql.getValue().contains("'superseded'"));
        assertTrue(sql.getValue().contains("max(CAST(NULLIF(metadata->>'version', '') AS INTEGER))"));
    }
}
