package com.charles.kb.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问答：问题向量化 -&gt; 找最像的 5 块 -&gt; 把块 + 问题发给大模型，照着资料回答并标引用。
 * minScore=0.55：相关度太低的块直接丢掉，帮模型说“不知道”，而不是拿不相关的块硬编。
 */
@Service
public class RagService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> store;
    private final ChatModel chatModel;

    public RagService(EmbeddingModel embeddingModel,
                      EmbeddingStore<TextSegment> store,
                      ChatModel chatModel) {
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.chatModel = chatModel;
    }

    public record Hit(String source, String content, double score) {
    }

    public record Answer(String answer, List<Hit> sources) {
    }

    private static final String SYSTEM = """
            只根据下面提供的资料回答问题。
            如果资料里没有答案，就说“资料里没找到”，不要编造。
            回答时用 [1]、[2] 这样的编号标出引用来源。""";

    public Answer ask(String question) {
        var queryEmbedding = embeddingModel.embed(question).content();

        List<Hit> hits = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(5)
                        .minScore(0.55)
                        .build())
                .matches().stream()
                .map(m -> new Hit(
                        m.embedded().metadata().getString(IngestService.SOURCE),
                        m.embedded().text(),
                        m.score()))
                .toList();

        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            ctx.append("[").append(i + 1).append("] (")
                    .append(hits.get(i).source()).append(")\n")
                    .append(hits.get(i).content()).append("\n\n");
        }

        String prompt = "资料：\n" + ctx + "\n问题：" + question;
        String answer = chatModel.chat(
                        SystemMessage.from(SYSTEM),
                        UserMessage.from(prompt))
                .aiMessage().text();
        return new Answer(answer, hits);
    }
}
