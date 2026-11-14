package com.charles.kb.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 建库：切块 -&gt; 向量化 -&gt; 存 pgvector，三步由 EmbeddingStoreIngestor 一次做完。
 * source 元数据（文件名）会跟着每个块存进 metadata，供回答时标引用。
 */
@Service
public class IngestService {

    public static final String SOURCE = "source";

    private final DocumentSplitter splitter;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;

    public IngestService(DocumentSplitter splitter,
                         EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> store) {
        this.splitter = splitter;
        this.embeddingModel = embeddingModel;
        this.store = store;
    }

    /** 返回切出的块数。同一文件重复上传会产生重复块，第一步先不管，后面再做去重/增量更新。 */
    public int ingest(String source, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        AtomicInteger chunks = new AtomicInteger();
        EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .textSegmentTransformer(segment -> {
                    chunks.incrementAndGet();
                    return segment;
                })
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build()
                .ingest(Document.from(text, Metadata.from(SOURCE, source)));
        return chunks.get();
    }
}
