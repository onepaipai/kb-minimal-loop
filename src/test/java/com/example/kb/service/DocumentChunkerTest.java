package com.example.kb.service;

import java.util.List;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 结构感知切块：标题带章节、表格与围栏不拦腰切、重叠只在句末断开。 */
class DocumentChunkerTest {

@Test
void headingsBecomeSectionPrefixAndMetadata() {
String text = "# 请假制度\n\n## 年假\n\n" + "年假每年 10 天，入职满一年可用。".repeat(8)
+ "\n\n## 病假\n\n" + "病假凭医院证明请假。".repeat(8);
List<TextSegment> segments = new DocumentChunker(200, 40).split(Document.from(text));
assertEquals(2, segments.size());
assertEquals("请假制度 > 病假", segments.get(1).metadata().getString(MetadataKeys.SECTION));
assertTrue(segments.get(1).text().startsWith(""));
assertTrue(segments.get(0).text().contains("## 年假"));
}

@Test
void tableIsNeverSplitMidway() {
String text = "# 假期表\n\n| 姓名 | 天数 |\n| --- | --- |\n| 张三 | 10 |\n| 李四 | 5 |\n| 王五 | 15 |\n";
List<TextSegment> segments = new DocumentChunker(500, 50).split(Document.from(text));
assertEquals(1, segments.size());
assertTrue(segments.get(0).text().contains("| 张三 | 10 |"));
assertTrue(segments.get(0).text().contains("| 王五 | 15 |"));
}

@Test
void overlapEndsAtSentenceBoundary() {
String text = "年假每年 10 天。".repeat(30) + "\n\n" + "病假凭医院证明请假。".repeat(25);
List<TextSegment> segments = new DocumentChunker(400, 100).split(Document.from(text));
assertEquals(2, segments.size());
assertTrue(segments.get(1).text().startsWith("年假每年 10 天。"));
assertTrue(segments.get(1).text().contains("病假凭医院证明请假。"));
}

@Test
void chunkIndexAndTotalAreRecorded() {
String text = "第一段。".repeat(100) + "\n\n" + "第二段。".repeat(100) + "\n\n" + "第三段。".repeat(100);
List<TextSegment> segments = new DocumentChunker(200, 40).split(Document.from(text));
assertEquals(6, segments.size());
for (int i = 0; i < segments.size(); i++) {
assertEquals(String.valueOf(i), segments.get(i).metadata().getString(MetadataKeys.CHUNK_INDEX));
assertEquals("6", segments.get(i).metadata().getString(MetadataKeys.CHUNK_TOTAL));
assertTrue(segments.get(i).text().length() <= 200);
}
}

@Test
void blankTextReturnsEmpty() {
Document blank = new Document() {
@Override
public String text() {
return " \n ";
}

@Override
public Metadata metadata() {
return new Metadata();
}
};
assertTrue(new DocumentChunker(200, 40).split(blank).isEmpty());
}

@Test
void singleLongSentenceIsHardCut() {
List<TextSegment> segments = new DocumentChunker(200, 40).split(Document.from("啊".repeat(500)));
assertEquals(3, segments.size());
for (TextSegment segment: segments) {
assertTrue(segment.text().length() <= 200);
}
}

@Test
void codeFenceStaysWhole() {
String text = "# 示例\n\n```java\n" + "int a = 1;\n".repeat(10) + "```\n\n" + "说明文字。".repeat(5);
List<TextSegment> segments = new DocumentChunker(500, 50).split(Document.from(text));
assertEquals(1, segments.size());
assertTrue(segments.get(0).text().contains("```java"));
assertTrue(segments.get(0).text().contains("int a = 1;"));
}
}
