package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 超大方法按空行断段：每段不超 8000 字符，且不从行中间切断。 */
@ExtendWith(MockitoExtension.class)
class CodeIngestServiceTest {

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    DocumentManageService documentManageService;

    @Mock
    DocumentBlobService documentBlobService;

    @Test
    void oversizedMethodSplitsAtBlankLines() throws Exception {
        String block = "        System.out.println(\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\");\n".repeat(60);
        String source = "package com.example;\n\npublic class Big {\n    void big() {\n"
                + block + "\n" + block + "\n" + block + "    }\n}\n";
        when(documentManageService.reserveVersion(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(documentManageService.countBySource(anyString())).thenReturn(4);
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[] {0f}))));
        CodeIngestService service = new CodeIngestService(embeddingModel, embeddingStore, documentManageService, documentBlobService);
        service.ingestJavaBytes(source.getBytes(StandardCharsets.UTF_8), "Big.java", "all", "approved");
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        List<String> methodParts = captor.getValue().stream()
                .filter(segment -> "big".equals(segment.metadata().getString("method")))
                .map(segment -> segment.text().substring(segment.text().indexOf('\n') + 1))
                .toList();
        assertTrue(methodParts.size() >= 2, "method should split into parts: " + methodParts.size());
        for (String part : methodParts) {
            assertTrue(part.length() <= 8000, "part too long: " + part.length());
        }
        Set<String> sourceLines = Set.copyOf(List.of(source.split("\n", -1)));
        for (String part : methodParts) {
            for (String line : part.split("\n", -1)) {
                assertTrue(sourceLines.contains(line), "cut mid-line: " + line);
            }
        }
    }
}
