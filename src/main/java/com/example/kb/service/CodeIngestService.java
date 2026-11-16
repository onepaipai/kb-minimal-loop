package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentManageService documentManageService;

    public CodeIngestService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                             DocumentManageService documentManageService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentManageService = documentManageService;
    }

    /** 先解析（语法错直接 400），成功后才删同名旧块再入库，失败时旧块不动。 */
    public IngestResponse ingestJava(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String source = new String(file.getBytes(), StandardCharsets.UTF_8);
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
        TypeDeclaration<?> primaryType = cu.getPrimaryType()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件中没有类定义"));
        String fullClassName = packageName.isEmpty()
                ? primaryType.getNameAsString()
                : packageName + "." + primaryType.getNameAsString();
        List<TextSegment> segments = buildSegments(fileName, fullClassName, packageName, source, cu);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件解析后没有可入库的代码块");
        }
        documentManageService.removeBySource(fileName);
        Response<List<Embedding>> embeddingsResponse = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddingsResponse.content(), segments);
        return IngestResponse.of(fileName, documentManageService.countBySource(fileName));
    }

    /** 类头块（第 1 行到第一个方法之前）加每个方法一块，块前加定位头。 */
    private List<TextSegment> buildSegments(String fileName, String fullClassName, String packageName,
                                            String source, CompilationUnit cu) {
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
                joinLines(lines, 1, firstMethodBegin - 1), "类头", "类头"));
        for (int i = 0; i < methods.size(); i++) {
            MethodDeclaration method = methods.get(i);
            segments.add(newSegment(fileName, fullClassName, packageName,
                    joinLines(lines, beginLines.get(i), endLines.get(i)),
                    method.getNameAsString(), method.getDeclarationAsString(true, true, true)));
        }
        return segments;
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
                                   String text, String method, String signature) {
        String header = "// 文件：" + fileName + " | 类：" + fullClassName + " | 方法：" + method;
        Metadata metadata = new Metadata();
        metadata.put(MetadataKeys.SOURCE, fileName);
        metadata.put("kind", "code");
        metadata.put("package", packageName);
        metadata.put("class", fullClassName);
        metadata.put("method", method);
        metadata.put("signature", signature);
        return new TextSegment(header + "\n" + text, metadata);
    }
}
