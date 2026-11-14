package com.example.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.example.kb.dto.IngestResponse;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Java 单文件代码知识库入库服务：JavaParser 只找边界，按 Range 行号切原文
 * （一个方法一块，类头单独一块），自主走 embedAll + addAll，
 * 不复用绑死递归切块的 EmbeddingStoreIngestor。
 */
@Service
public class CodeIngestService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public CodeIngestService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 解析 Java 单文件并入库：先解析（语法错直接 400），成功后才删同名旧块再入库，失败时旧块不动。
     */
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
        String packageName = "";
        if (cu.getPackageDeclaration().isPresent()) {
            packageName = cu.getPackageDeclaration().get().getNameAsString();
        }
        if (cu.getPrimaryType().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件中没有类定义");
        }
        String className = cu.getPrimaryType().get().getNameAsString();
        String fullClassName = className;
        if (!packageName.isEmpty()) {
            fullClassName = packageName + "." + className;
        }
        List<TextSegment> segments = buildSegments(fileName, fullClassName, packageName, source, cu);
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件解析后没有可入库的代码块");
        }
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(fileName));
        Response<List<Embedding>> embeddingsResponse = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddingsResponse.content(), segments);
        IngestResponse response = new IngestResponse();
        response.setFile(fileName);
        response.setChunks(segments.size());
        return response;
    }

    /**
     * 按行区间切原文成块：类头块（第 1 行到第一个方法声明之前，含包名、import、类注释、类声明、字段）
     * 加每个方法一块（含方法 javadoc），块前加定位头。
     */
    private List<TextSegment> buildSegments(String fileName, String fullClassName, String packageName,
                                            String source, CompilationUnit cu) {
        List<String> lines = List.of(source.split("\n", -1));
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        // 先算出每个方法的起止行（含 javadoc），类头块切到最小起始行之前；
        // Range 理论上必然存在，缺失时按 400 兜底而不是抛 500
        List<Integer> beginLines = new ArrayList<>();
        List<Integer> endLines = new ArrayList<>();
        int firstMethodBegin = lines.size() + 1;
        for (MethodDeclaration method : methods) {
            if (method.getRange().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Java 文件解析结果缺少方法位置信息");
            }
            int begin = method.getRange().get().begin.line;
            int end = method.getRange().get().end.line;
            if (method.getJavadocComment().isPresent()) {
                JavadocComment javadoc = method.getJavadocComment().get();
                if (javadoc.getRange().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Java 文件解析结果缺少注释位置信息");
                }
                begin = javadoc.getRange().get().begin.line;
            }
            beginLines.add(begin);
            endLines.add(end);
            if (begin < firstMethodBegin) {
                firstMethodBegin = begin;
            }
        }
        List<TextSegment> segments = new ArrayList<>();
        segments.add(newSegment(fileName, fullClassName, packageName,
                joinLines(lines, 1, firstMethodBegin - 1), "类头", "类头"));
        for (int i = 0; i < methods.size(); i++) {
            MethodDeclaration method = methods.get(i);
            String signature = method.getDeclarationAsString(true, true, true);
            segments.add(newSegment(fileName, fullClassName, packageName,
                    joinLines(lines, beginLines.get(i), endLines.get(i)),
                    method.getNameAsString(), signature));
        }
        return segments;
    }

    /**
     * 按行号区间（1 起）从原文件行列表切原文，保留注释与格式，不重打代码。
     */
    private String joinLines(List<String> lines, int beginLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int i = beginLine; i <= endLine; i++) {
            builder.append(lines.get(i - 1)).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    /**
     * 拼定位头与元数据：source/kind/package/class/method/signature。
     */
    private TextSegment newSegment(String fileName, String fullClassName, String packageName,
                                   String text, String method, String signature) {
        String header = "// 文件：" + fileName + " | 类：" + fullClassName + " | 方法：" + method;
        Metadata metadata = new Metadata();
        metadata.put("source", fileName);
        metadata.put("kind", "code");
        metadata.put("package", packageName);
        metadata.put("class", fullClassName);
        metadata.put("method", method);
        metadata.put("signature", signature);
        return new TextSegment(header + "\n" + text, metadata);
    }
}
