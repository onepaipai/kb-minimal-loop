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

    @Test
    void sameContentSkipsWithoutEmbedding() throws Exception {
        byte[] bytes = "请假制度：年假十五天。".getBytes(StandardCharsets.UTF_8);
        when(documentManageService.findFileHash("a.txt")).thenReturn(IngestService.sha256Hex(bytes));
        when(documentManageService.countBySource("a.txt")).thenReturn(3);
        IngestService service = new IngestService(documentSplitter, embeddingModel, embeddingStore,
                documentManageService);

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
                documentManageService);

        var response = service.ingestBytes(bytes, "a.txt", "all", "approved");

        assertFalse(response.isSkipped());
        assertEquals(2, response.getChunks());
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertEquals(IngestService.sha256Hex(bytes),
                captor.getValue().get(0).metadata().getString(MetadataKeys.FILE_HASH));
    }
}
