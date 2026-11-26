package com.example.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 原件存储：开关关闭、超过体积上限时跳过写库；正常范围内才写。 */
@ExtendWith(MockitoExtension.class)
class DocumentBlobServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void disabledSkipsSave() {
        DocumentBlobService service = new DocumentBlobService(jdbcTemplate, false, 10);

        service.save("a.txt", 1, "a.txt", "text/plain", "内容".getBytes());

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void oversizedSkipsSave() {
        DocumentBlobService service = new DocumentBlobService(jdbcTemplate, true, 1);

        service.save("big.pdf", 1, "big.pdf", "application/pdf", new byte[2 * 1024 * 1024]);

        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class));
    }

    @Test
    void normalFileSaves() {
        DocumentBlobService service = new DocumentBlobService(jdbcTemplate, true, 10);

        service.save("a.txt", 1, "a.txt", "text/plain", "内容".getBytes());

        verify(jdbcTemplate).update(any(PreparedStatementCreator.class));
    }
}
