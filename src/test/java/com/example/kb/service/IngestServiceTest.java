package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 重复 ingest 去重：同名同内容直接跳过，内容变了仍走先入后删替换。 */
@ExtendWith(MockitoExtension.class)
class IngestServiceTest {

    @Mock
    private DocumentSplitter documentSplitter;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private DocumentManageService documentManageService;
    @Mock
    private DocumentBlobService documentBlobService;

    @Test
    void sameContentSkipsWithoutEmbedding() throws Exception {
        byte[] bytes = "请假制度：年假十五天。".getBytes(StandardCharsets.UTF_8);
        when(documentManageService.findFileHash("a.txt")).thenReturn(IngestService.sha256Hex(bytes));
        when(documentManageService.countBySource("a.txt")).thenReturn(3);
        IngestService service = new IngestService(documentSplitter, embeddingModel, embeddingStore,
                documentManageService, documentBlobService);

        var response = service.ingestBytes(bytes, "a.txt", "all", "approved");

        assertTrue(response.isSkipped());
        assertEquals(3, response.getChunks());
        verifyNoInteractions(documentSplitter, embeddingModel, embeddingStore);
        verify(documentManageService, never()).nextVersion(anyString());
    }

    @Test
    void changedContentReingestsAndStoresHash() throws Exception {
        byte[] bytes = "请假制度：年假十五天。".getBytes(StandardCharsets.UTF_8);
        when(documentManageService.findFileHash("a.txt")).thenReturn("old-hash");
        when(documentManageService.nextVersion("a.txt")).thenReturn(1);
        when(documentManageService.countBySource("a.txt")).thenReturn(2);
        // 模拟真实切块器：段带上文档元数据（含 file_hash）。
        // 注意：EmbeddingStoreIngestor 调的是 splitAll（默认方法会调 split，但 mock 不走默认方法），所以要 stub splitAll
        when(documentSplitter.splitAll(anyList())).thenAnswer(inv -> {
            List<Document> documents = inv.getArgument(0);
            return documents.stream()
                    .map(document -> TextSegment.from(document.text(), document.metadata()))
                    .toList();
        });
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[] {0f}))));
        IngestService service = new IngestService(documentSplitter, embeddingModel, embeddingStore,
                documentManageService, documentBlobService);

        var response = service.ingestBytes(bytes, "a.txt", "all", "approved");

        assertFalse(response.isSkipped());
        assertEquals(2, response.getChunks());
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertEquals(IngestService.sha256Hex(bytes),
                captor.getValue().get(0).metadata().getString(MetadataKeys.FILE_HASH));
        // 新版先以 pending 入库，发布那一步再改成目标状态：入库中途断了旧版还活着
        assertEquals("pending", captor.getValue().get(0).metadata().getString(MetadataKeys.STATUS));
        verify(documentManageService).publishVersion(eq("a.txt"), anyString(), eq("approved"));
    }

    @Test
    void xlsxParsesThroughTika() throws Exception {
        org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("制度");
        sheet.createRow(0).createCell(0).setCellValue("请假制度：年假十五天");
        byte[] bytes = toBytes(workbook);
        workbook.close();
        when(documentManageService.findFileHash("假期.xlsx")).thenReturn(null);
        when(documentManageService.nextVersion("假期.xlsx")).thenReturn(1);
        when(documentManageService.countBySource("假期.xlsx")).thenReturn(1);
        when(documentSplitter.splitAll(anyList())).thenAnswer(inv -> {
            List<Document> documents = inv.getArgument(0);
            return documents.stream()
                    .map(document -> TextSegment.from(document.text(), document.metadata()))
                    .toList();
        });
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[] {0f}))));
        IngestService service = new IngestService(documentSplitter, embeddingModel, embeddingStore,
                documentManageService, documentBlobService);

        var response = service.ingestBytes(bytes, "假期.xlsx", "all", "approved");

        assertFalse(response.isSkipped());
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("请假制度"));
    }

    @Test
    void pptxParsesThroughTika() throws Exception {
        org.apache.poi.xslf.usermodel.XMLSlideShow slideshow = new org.apache.poi.xslf.usermodel.XMLSlideShow();
        org.apache.poi.xslf.usermodel.XSLFSlide slide = slideshow.createSlide();
        slide.createTextBox().setText("请假制度：年假十五天");
        byte[] bytes = toBytes(slideshow);
        slideshow.close();
        when(documentManageService.findFileHash("假期.pptx")).thenReturn(null);
        when(documentManageService.nextVersion("假期.pptx")).thenReturn(1);
        when(documentManageService.countBySource("假期.pptx")).thenReturn(1);
        when(documentSplitter.splitAll(anyList())).thenAnswer(inv -> {
            List<Document> documents = inv.getArgument(0);
            return documents.stream()
                    .map(document -> TextSegment.from(document.text(), document.metadata()))
                    .toList();
        });
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[] {0f}))));
        IngestService service = new IngestService(documentSplitter, embeddingModel, embeddingStore,
                documentManageService, documentBlobService);

        var response = service.ingestBytes(bytes, "假期.pptx", "all", "approved");

        assertFalse(response.isSkipped());
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("请假制度"));
    }

    private static byte[] toBytes(java.io.Closeable workbook) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook wb) {
            wb.write(out);
        } else if (workbook instanceof org.apache.poi.xslf.usermodel.XMLSlideShow ppt) {
            ppt.write(out);
        }
        return out.toByteArray();
    }
}
