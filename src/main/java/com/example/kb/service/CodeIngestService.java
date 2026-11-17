package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.kb.dto.IngestResponse;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Java 单文件代码知识库入库：JavaParser 只找边界，按 Range 行号切原文，
 * 一个方法一块，类头单独一块，不复用绑死递归切块的 EmbeddingStoreIngestor。
 */
@Service
public class CodeIngestService {

    /** 单个方法块超过该字符数时按行拆成多块，避免超大方法一个块占满上下文 */
    private static final int MAX_METHOD_CHARS = 8000;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentManageService documentManageService;

    public CodeIngestService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                             DocumentManageService documentManageService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentManageService = documentManageService;
    }

    /** 先解析（语法错直接 400），成功后先入新块再删旧块，失败时旧块不动。 */
    public IngestResponse ingestJava(MultipartFile file, String visibility, String status) throws IOException {
        return ingestJavaBytes(file.getBytes(), file.getOriginalFilename(), visibility, status);
    }

    /** 同 ingestJava，但入参是已读进内存的字节数组，供异步入库在后台线程调用。 */
    public IngestResponse ingestJavaBytes(byte[] bytes, String fileName, String visibility, String status) throws IOException {
        String source = new String(bytes, StandardCharsets.UTF_8);
        if (source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容为空");
        }
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (ParseProblemException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Java 文件解析失败（语法错误）");
        }
        String packageName = cu.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        // 注意：getPrimaryType() 是按文件名找主类，从字节数组解析时没有文件名，永远为空；这里取第一个 public 类，没有再取第一个类
        TypeDeclaration<?> primaryType = cu.getTypes().stream()
                .filter(TypeDeclaration::isPublic)
                .findFirst()
                .or(() -> cu.getTypes().stream().findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件中没有类定义"));
        String fullClassName = packageName.isEmpty()
                ? primaryType.getNameAsString()
                : packageName + "." + primaryType.getNameAsString();
        String batch = UUID.randomUUID().toString();
        int version = documentManageService.nextVersion(fileName);
        List<TextSegment> segments = buildSegments(fileName, fullClassName, packageName, source, cu, visibility, batch,
                status, version);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件解析后没有可入库的代码块");
        }
        Response<List<Embedding>> embeddingsResponse = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddingsResponse.content(), segments);
        documentManageService.removeReplaced(fileName, batch);
        return IngestResponse.of(fileName, documentManageService.countBySource(fileName), visibility, status);
    }

    /** 类头块（第 1 行到第一个方法之前）加每个方法一块，块前加定位头。 */
    private List<TextSegment> buildSegments(String fileName, String fullClassName, String packageName,
                                            String source, CompilationUnit cu, String visibility, String batch,
                                            String status, int version) {
        List<String> lines = List.of(source.split("\n", -1));
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        List<Integer> beginLines = new ArrayList<>();
        List<Integer> endLines = new ArrayList<>();
        int firstMethodBegin = lines.size() + 1;
        for (MethodDeclaration method : methods) {
            int begin = methodBeginLine(method);
            beginLines.add(begin);
            endLines.add(methodRange(method).end.line);
            firstMethodBegin = Math.min(firstMethodBegin, begin);
        }
        List<TextSegment> segments = new ArrayList<>();
        segments.add(newSegment(fileName, fullClassName, packageName,
                joinLines(lines, 1, firstMethodBegin - 1), "类头", "类头", visibility, batch, status, version));
        for (int i = 0; i < methods.size(); i++) {
            MethodDeclaration method = methods.get(i);
            // 嵌套类的方法归属最近一层类，不再都标成外层类
            String methodClass = method.findAncestor(TypeDeclaration.class)
                    .map(type -> packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString())
                    .orElse(fullClassName);
            for (String part : splitMethodText(lines, beginLines.get(i), endLines.get(i))) {
                segments.add(newSegment(fileName, methodClass, packageName, part,
                        method.getNameAsString(), method.getDeclarationAsString(true, true, true), visibility, batch,
                        status, version));
            }
        }
        return segments;
    }

    /** 超大方法先按空行断成逻辑段再打包，每段不超过 MAX_METHOD_CHARS 字符（javadoc 留在第一段）；单段仍超长才按行硬切。 */
    private List<String> splitMethodText(List<String> lines, int beginLine, int endLine) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs(lines, beginLine, endLine)) {
            if (paragraph.length() > MAX_METHOD_CHARS) {
                flushMethodPart(parts, current);
                parts.addAll(splitLines(paragraph));
                continue;
            }
            if (current.length() > 0 && current.length() + 1 + paragraph.length() > MAX_METHOD_CHARS) {
                flushMethodPart(parts, current);
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(paragraph);
        }
        flushMethodPart(parts, current);
        return parts;
    }

    private void flushMethodPart(List<String> parts, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        parts.add(current.toString().stripTrailing());
        current.setLength(0);
    }

    /** 方法体内的空行是逻辑段的分界（一段声明、一段循环、一段返回），段内不切。 */
    private List<String> paragraphs(List<String> lines, int beginLine, int endLine) {
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = beginLine; i <= endLine; i++) {
            String line = lines.get(i - 1);
            if (line.isBlank()) {
                if (current.length() > 0) {
                    paragraphs.add(current.toString().stripTrailing());
                    current = new StringBuilder();
                }
                continue;
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
        }
        if (current.length() > 0) {
            paragraphs.add(current.toString().stripTrailing());
        }
        return paragraphs;
    }

    /** 单段超长时的兜底：按行硬切，每段不超过 MAX_METHOD_CHARS 字符。 */
    private List<String> splitLines(String paragraph) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : paragraph.split("\n", -1)) {
            if (current.length() > 0 && current.length() + line.length() + 1 > MAX_METHOD_CHARS) {
                parts.add(current.toString().stripTrailing());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().stripTrailing());
        }
        return parts;
    }

    /** 方法起始行：有 javadoc 时从 javadoc 起，含注释一起切。 */
    private int methodBeginLine(MethodDeclaration method) {
        Range range = methodRange(method);
        return method.getJavadocComment()
                .map(javadoc -> javadocRange(javadoc).begin.line)
                .orElse(range.begin.line);
    }

    private Range methodRange(MethodDeclaration method) {
        return method.getRange()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Java 文件解析结果缺少方法位置信息"));
    }

    private Range javadocRange(JavadocComment javadoc) {
        return javadoc.getRange()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Java 文件解析结果缺少注释位置信息"));
    }

    /** 按行号区间（1 起）从原文件行列表切原文，保留注释与格式。 */
    private String joinLines(List<String> lines, int beginLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int i = beginLine; i <= endLine; i++) {
            builder.append(lines.get(i - 1)).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    private TextSegment newSegment(String fileName, String fullClassName, String packageName,
                                   String text, String method, String signature, String visibility, String batch,
                                   String status, int version) {
        String header = "// 文件：" + fileName + " | 类：" + fullClassName + " | 方法：" + method;
        Metadata metadata = new Metadata();
        metadata.put(MetadataKeys.SOURCE, fileName);
        metadata.put(MetadataKeys.VISIBILITY, visibility);
        metadata.put(MetadataKeys.BATCH, batch);
        metadata.put(MetadataKeys.STATUS, status);
        metadata.put(MetadataKeys.VERSION, String.valueOf(version));
        metadata.put("kind", "code");
        metadata.put("package", packageName);
        metadata.put("class", fullClassName);
        metadata.put("method", method);
        metadata.put("signature", signature);
        return new TextSegment(header + "\n" + text, metadata);
    }
}
