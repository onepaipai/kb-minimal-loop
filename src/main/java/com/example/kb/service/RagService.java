package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

/**
 * 检索问答服务：问题向量化 → 检索最像的 5 块 → 拼引用上下文 → 聊天模型作答。
 */
@Service
public class RagService {

    /** 系统提示词写死：只按资料作答，资料里没有就明说，回答带 [1][2] 引用 */
    private static final String SYSTEM_PROMPT =
            "你是企业知识库问答助手。你只能根据下面提供的资料回答问题；"
                    + "如果资料里没有相关内容，必须回答“资料里没找到”，不要编造任何信息。"
                    + "回答时必须在对应内容后面标注引用编号，格式如 [1][2]。";

    private static final int MAX_RESULTS = 5;
    private static final double MIN_SCORE = 0.55;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;

    public RagService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore, ChatModel chatModel) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
    }

    /**
     * 检索并回答：把检索到的块拼成带编号的资料段连同问题一起发给模型。
     */
    public AskResponse ask(AskRequest request) {
        Response<Embedding> embeddingResponse = embeddingModel.embed(request.getQuestion());
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingResponse.content())
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

        List<String> sources = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            TextSegment segment = matches.get(i).embedded();
            contextBuilder.append("[").append(i + 1).append("] ").append(segment.text()).append("\n\n");
            sources.add(segment.metadata().getString("source"));
        }
        String prompt = "资料：\n" + contextBuilder + "问题：" + request.getQuestion();

        List<ChatMessage> messages = List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(prompt));
        String answer = chatModel.chat(messages).aiMessage().text();

        AskResponse response = new AskResponse();
        response.setAnswer(answer);
        response.setSources(sources);
        return response;
    }
}
