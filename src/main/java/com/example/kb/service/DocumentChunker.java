package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

/**
 * 结构感知切块器，替代通用递归切块：按标题、表格、代码围栏、段落分块，
 * 每块带章节路径前缀与块元数据；重叠只在句末断开，不断句；表格与代码围栏不拦腰切。
 * 块上限是硬上限（maxChars），重叠部分不计入；maxChars/overlapChars 由配置传入。
 */
public class DocumentChunker implements DocumentSplitter {

    private static final String SECTION_PREFIX_FORMAT = "\n";

    private final DocumentBlockSplitter blockSplitter;
    private final DocumentBlockPacker blockPacker;

    public DocumentChunker(int maxChars, int overlapChars) {
        this.blockSplitter = new DocumentBlockSplitter();
        this.blockPacker = new DocumentBlockPacker(maxChars, overlapChars);
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Block> chunks = blockPacker.pack(blockPacker.toPieces(blockSplitter.splitIntoBlocks(text)));
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            segments.add(toSegment(chunks.get(i), i, chunks.size(), document.metadata()));
        }
        return segments;
    }

    /** 块转正文段：带章节前缀，元数据带块号、块总数与章节。 */
    private TextSegment toSegment(Block chunk, int index, int total, Metadata base) {
        Metadata metadata = base.copy();
        metadata.put(MetadataKeys.CHUNK_INDEX, String.valueOf(index));
        metadata.put(MetadataKeys.CHUNK_TOTAL, String.valueOf(total));
        String body = chunk.text();
        if (!chunk.section().isEmpty()) {
            metadata.put(MetadataKeys.SECTION, chunk.section());
            body = String.format(SECTION_PREFIX_FORMAT, chunk.section()) + body;
        }
        return new TextSegment(body, metadata);
    }
}
