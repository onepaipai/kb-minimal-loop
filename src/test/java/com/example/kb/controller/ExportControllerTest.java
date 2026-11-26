package com.example.kb.controller;

import com.example.kb.service.ExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 导出接口：dataset / format 白名单校验，非法值一律 400。 */
@ExtendWith(MockitoExtension.class)
class ExportControllerTest {

    @Mock
    private ExportService exportService;

    @Test
    void rejectsUnknownDataset() {
        ExportController controller = new ExportController(exportService);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.export("unknown", "csv"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void rejectsUnknownFormat() {
        ExportController controller = new ExportController(exportService);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.export("audit", "xlsx"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
