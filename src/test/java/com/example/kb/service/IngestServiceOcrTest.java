package com.example.kb.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扫描版 PDF 的 OCR：Tika 自带 Tesseract 解析器，机器上装了 tesseract 才跑 OCR，
 * 没装时解析不出文本（走原来的 400）。测试只在装了 tesseract 的机器上跑，没装就跳过。
 * 扫描版 PDF 在测试里现做（文字画成图片再嵌进 PDF，不带文本层），不提交二进制 fixture。
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceOcrTest {

    @Mock
    private DocumentSplitter documentSplitter;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private DocumentManageService documentManageService;

    @Test
    void scannedPdfOcrReadsImageText() throws Exception {
        assumeTrue(new java.io.File("/usr/bin/tesseract").exists()
                        || new java.io.File("/usr/local/bin/tesseract").exists(),
                "本机没装 tesseract，跳过 OCR 测试");
        byte[] bytes = scannedPdf("Leave policy: 15 days annual leave.");
        when(documentManageService.findFileHash("scanned.pdf")).thenReturn(null);
        when(documentManageService.nextVersion("scanned.pdf")).thenReturn(1);
        when(documentManageService.countBySource("scanned.pdf")).thenReturn(1);
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

        var response = service.ingestBytes(bytes, "scanned.pdf", "all", "approved");

        assertFalse(response.isSkipped());
        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(anyList(), captor.capture());
        assertTrue(captor.getValue().get(0).text().contains("Leave policy"),
                "OCR 没读出图片里的文字，实际解析结果：" + captor.getValue().get(0).text());
    }

    /** 文字画成图片再嵌进一页 PDF（纯图片，无文本层，就是扫描版的效果）。 */
    private static byte[] scannedPdf(String text) throws Exception {
        BufferedImage image = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1200, 300);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 48));
        graphics.drawString(text, 40, 150);
        graphics.dispose();
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(1200, 300));
            document.addPage(page);
            PDImageXObject pdfImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(pdfImage, 0, 0, 1200, 300);
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
