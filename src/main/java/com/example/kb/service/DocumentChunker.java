package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");
private static final String SECTION_PREFIX_FORMAT = "\n";
/** 重叠尾巴太短就没有上下文价值，直接不要。 */
private static final int MIN_OVERLAP_CHARS = 20;

private final int maxChars;
private final int overlapChars;

public DocumentChunker(int maxChars, int overlapChars) {
this.maxChars = maxChars;
this.overlapChars = overlapChars;
}

/** 一块原文：section 为标题路径（无标题为空串），atomic 为表格/代码围栏（不拦腰切）。 */
private record Block(String text, String section, boolean atomic) {
}

@Override
public List<TextSegment> split(Document document) {
String text = document.text();
if (text == null || text.isBlank()) {
return List.of();
}
List<Block> chunks = pack(toPieces(splitIntoBlocks(text)));
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

/** 按行扫原文：标题更新章节栈并随下文走，表格与代码围栏各成一块，其余按空行分段。 */
private List<Block> splitIntoBlocks(String text) {
String[] lines = text.split("\n", -1);
List<Block> blocks = new ArrayList<>();
String[] headings = new String[7];
StringBuilder paragraph = new StringBuilder();
String paragraphSection = "";
for (int i = 0; i < lines.length; i++) {
String line = lines[i];
Matcher heading = HEADING.matcher(line);
if (heading.matches()) {
flushParagraph(blocks, paragraph, paragraphSection);
int level = heading.group(1).length();
headings[level] = heading.group(2);
clearDeeper(headings, level);
paragraphSection = sectionOf(headings);
paragraph.append(line);
continue;
}
if (FENCE.matcher(line).matches()) {
flushParagraph(blocks, paragraph, paragraphSection);
i = addFenceBlock(blocks, lines, i, sectionOf(headings));
continue;
}
if (isTableLine(line)) {
flushParagraph(blocks, paragraph, paragraphSection);
i = addTableBlock(blocks, lines, i, sectionOf(headings));
continue;
}
if (line.isBlank()) {
flushParagraph(blocks, paragraph, paragraphSection);
continue;
}
if (paragraph.length() == 0) {
paragraphSection = sectionOf(headings);
} else {
paragraph.append("\n");
}
paragraph.append(line);
}
flushParagraph(blocks, paragraph, paragraphSection);
return blocks;
}

private void flushParagraph(List<Block> blocks, StringBuilder paragraph, String section) {
if (paragraph.length() == 0) {
return;
}
blocks.add(new Block(paragraph.toString(), section, false));
paragraph.setLength(0);
}

private void clearDeeper(String[] headings, int level) {
for (int i = level + 1; i < headings.length; i++) {
headings[i] = null;
}
}

private String sectionOf(String[] headings) {
StringBuilder section = new StringBuilder();
for (int i = 1; i < headings.length; i++) {
if (headings[i] == null) {
continue;
}
if (section.length() > 0) {
section.append(" > ");
}
section.append(headings[i]);
}
return section.toString();
}

private boolean isTableLine(String line) {
return line.stripLeading().startsWith("|");
}

/** 从第 i 行起收一整个代码围栏（含首尾围栏行），返回围栏结束行的下标。 */
private int addFenceBlock(List<Block> blocks, String[] lines, int i, String section) {
StringBuilder fence = new StringBuilder(lines[i]);
i++;
while (i < lines.length &&!FENCE.matcher(lines[i]).matches()) {
fence.append("\n").append(lines[i]);
i++;
}
if (i < lines.length) {
fence.append("\n").append(lines[i]);
}
blocks.add(new Block(fence.toString(), section, true));
return i;
}

/** 从第 i 行起收连续的表格行，返回最后一行表格的下标。 */
private int addTableBlock(List<Block> blocks, String[] lines, int i, String section) {
StringBuilder table = new StringBuilder(lines[i]);
i++;
while (i < lines.length && isTableLine(lines[i])) {
table.append("\n").append(lines[i]);
i++;
}
blocks.add(new Block(table.toString(), section, true));
return i - 1;
}

/** 超长块先拆成不超上限的片段：段落按句打包，表格按行分组，围栏按行分组，单句超长才硬切。 */
private List<Block> toPieces(List<Block> blocks) {
List<Block> pieces = new ArrayList<>();
for (Block block: blocks) {
if (block.text().length() <= maxChars) {
pieces.add(block);
} else if (block.atomic() && isTableLine(block.text())) {
splitTable(block, pieces);
} else if (block.atomic()) {
splitFence(block, pieces);
} else {
splitParagraph(block, pieces);
}
}
return pieces;
}

private void splitParagraph(Block block, List<Block> pieces) {
StringBuilder current = new StringBuilder();
for (String sentence: sentences(block.text())) {
if (sentence.length() > maxChars) {
flushPiece(pieces, current, block.section());
cutHard(sentence, block.section(), pieces);
continue;
}
if (current.length() > 0 && current.length() + sentence.length() > maxChars) {
flushPiece(pieces, current, block.section());
}
current.append(sentence);
}
flushPiece(pieces, current, block.section());
}

private void flushPiece(List<Block> pieces, StringBuilder current, String section) {
if (current.length() == 0) {
return;
}
pieces.add(new Block(current.toString(), section, false));
current.setLength(0);
}

/** 单句超长时按字符硬切（无标点可断的长串，如 URL 或长数字串）。 */
private void cutHard(String text, String section, List<Block> pieces) {
for (int from = 0; from < text.length(); from += maxChars) {
pieces.add(new Block(text.substring(from, Math.min(text.length(), from + maxChars)), section, false));
}
}

/** 表格按行分组：表头两行（标题 + 分隔行）每组都带，行本身超长才硬切。 */
private void splitTable(Block block, List<Block> pieces) {
String[] rows = block.text().split("\n", -1);
String header = rows[0];
if (rows.length > 1 && rows[1].strip().startsWith("|") && rows[1].contains("-")) {
header += "\n" + rows[1];
}
int firstDataRow = header.contains("\n")? 2: 1;
StringBuilder current = new StringBuilder(header);
for (int i = firstDataRow; i < rows.length; i++) {
if (rows[i].length() > maxChars) {
flushPiece(pieces, current, block.section());
cutHard(rows[i], block.section(), pieces);
current = new StringBuilder(header);
continue;
}
if (current.length() + 1 + rows[i].length() > maxChars) {
flushPiece(pieces, current, block.section());
current = new StringBuilder(header);
}
current.append("\n").append(rows[i]);
}
flushPiece(pieces, current, block.section());
}

/** 代码围栏按行分组：每组首尾都补围栏行，组内仍是原文行。 */
private void splitFence(Block block, List<Block> pieces) {
String[] rows = block.text().split("\n", -1);
String open = rows[0];
boolean closed = rows.length > 1 && FENCE.matcher(rows[rows.length - 1]).matches();
int lastBody = closed? rows.length - 1: rows.length;
StringBuilder current = new StringBuilder(open);
for (int i = 1; i < lastBody; i++) {
if (current.length() + 1 + rows[i].length() + 4 > maxChars) {
pieces.add(new Block(current + "\n```", block.section(), false));
current = new StringBuilder(open);
}
current.append("\n").append(rows[i]);
}
pieces.add(new Block(current + (closed? "\n```": ""), block.section(), false));
}

/** 片段贪心装块：装满即封块，下一块带上上一块句末结尾的重叠尾巴（放不下就不要）。 */
private List<Block> pack(List<Block> pieces) {
List<Block> chunks = new ArrayList<>();
StringBuilder current = new StringBuilder();
String currentSection = "";
String pendingTail = "";
for (Block piece: pieces) {
if (current.length() > 0 && current.length() + 2 + piece.text().length() > maxChars) {
chunks.add(new Block(current.toString(), currentSection, false));
pendingTail = overlapTail(current.toString());
current = new StringBuilder();
}
if (current.length() == 0) {
currentSection = piece.section();
if (!pendingTail.isEmpty() && pendingTail.length() + 2 + piece.text().length() <= maxChars) {
current.append(pendingTail).append("\n\n");
}
pendingTail = "";
} else {
current.append("\n\n");
}
current.append(piece.text());
}
if (current.length() > 0) {
chunks.add(new Block(current.toString(), currentSection, false));
}
return chunks;
}

/** 重叠尾巴：取末尾 overlapChars，从第一个完整句开始，太短就不要。 */
private String overlapTail(String body) {
String window = body.substring(Math.max(0, body.length() - overlapChars));
int firstEnd = firstSentenceEnd(window);
if (firstEnd < 0) {
return "";
}
String tail = window.substring(firstEnd + 1).stripLeading();
return tail.length() >= MIN_OVERLAP_CHARS? tail: "";
}

private int firstSentenceEnd(String text) {
for (int i = 0; i < text.length(); i++) {
if (isSentenceEnd(text, i)) {
return i;
}
}
return -1;
}

/** 按句切：中文句末标点、换行、英文句末标点加空格都算句界。 */
private List<String> sentences(String text) {
List<String> result = new ArrayList<>();
int start = 0;
for (int i = 0; i < text.length(); i++) {
if (isSentenceEnd(text, i)) {
result.add(text.substring(start, i + 1));
start = i + 1;
}
}
if (start < text.length()) {
result.add(text.substring(start));
}
return result;
}

private boolean isSentenceEnd(String text, int i) {
char c = text.charAt(i);
if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
return true;
}
if ((c == '.' || c == '!' || c == '?') && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
return true;
}
return false;
}
}
