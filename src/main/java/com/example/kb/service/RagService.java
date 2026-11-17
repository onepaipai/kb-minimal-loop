package com.example.kb.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.kb.common.Visibility;
import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.ReferenceHit;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Service;

/**
 * 检索问答服务：问题向量化 → 检索最像的 5 块 → 拼引用上下文 → 聊天模型作答。
 * ask() 等整段返回，askStream() 逐 token 流式推送，两者共用 prepare() 的检索与拼装逻辑。
 */
@Service
public class RagService {

    /** 系统提示词写死：只按本轮资料作答，历史对话仅用于理解指代，回答带 [1][2] 引用 */
    private static final String SYSTEM_PROMPT =
            "你是企业知识库问答助手。你只能根据本轮提供的资料回答问题；"
                    + "对话历史仅用于理解当前问题中的指代，历史回答不得作为答案来源；"
                    + "如果本轮资料里没有相关内容，必须回答“资料里没找到”，不要编造任何信息。"
                    + "回答时必须在对应内容后面标注引用编号，格式如 [1][2]。";

    private static final int MAX_RESULTS = 5;
    private static final double MIN_SCORE = 0.55;
    /** 每个会话最多保留的对话消息条数（约 10 轮） */
    private static final int MAX_MEMORY_MESSAGES = 20;
    /** 检索无命中时的固定回答：不调模型，保证每次都一样，也不给模型编造的机会 */
    static final String NOT_FOUND_ANSWER = "资料里没找到";

    /**
     * 问答的作用域：由 Controller 在请求线程里从登录用户解析好再传进来（流式问答跑在独立线程，拿不到请求线程的 SecurityContext，必须显式传）。
     * username 进会话键做隔离，department 是已按账号规则算好的生效部门。
     */
    public record AskScope(String username, String department) {
    }

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryStore chatMemoryStore;
    private final HybridSearchService hybridSearchService;

    public RagService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                      ChatModel chatModel, StreamingChatModel streamingChatModel, ChatMemoryStore chatMemoryStore,
                      HybridSearchService hybridSearchService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryStore = chatMemoryStore;
        this.hybridSearchService = hybridSearchService;
    }

    /** 流式问答的事件回调：Controller 接到 SseEmitter 上，顺序为 sources → token* → done / onError。 */
    public interface StreamListener {
        void onSources(List<ReferenceHit> hits);
        void onToken(String token);
        void onComplete();
        void onError(Throwable error);
    }

    /** ask() 与 askStream() 共用的准备结果：拼好的消息列表、会话记忆（无 sessionId 时为 null）、引用。 */
    private record PreparedAsk(List<ChatMessage> messages, ChatMemory chatMemory,
                               List<ReferenceHit> hits, List<String> sources) {
    }

    /**
     * 检索并回答：把检索到的块拼成带编号的资料段连同问题一起发给模型。
     * 无命中时不调模型，直接回“资料里没找到”；有命中时删掉答案里超出引用范围的编号。
     */
    public AskResponse ask(AskRequest request, AskScope scope) {
        PreparedAsk prepared = prepare(request, scope);
        String answer;
        if (prepared.hits().isEmpty()) {
            answer = NOT_FOUND_ANSWER;
        } else {
            answer = sanitizeCitations(chatModel.chat(prepared.messages()).aiMessage().text(),
                    prepared.hits().size());
        }
        saveMemory(prepared, request.getQuestion(), answer);

        AskResponse response = new AskResponse();
        response.setAnswer(answer);
        response.setSources(prepared.sources());
        response.setHits(prepared.hits());
        return response;
    }

    /** 删掉答案里超出本次引用范围的编号（如只有 3 块却写了 [9]），防止模型编造引用；范围内的不动。 */
    static String sanitizeCitations(String answer, int hitCount) {
        if (answer == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            int n = Integer.parseInt(matcher.group(1));
            String replacement = n >= 1 && n <= hitCount ? matcher.group(0) : "";
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 流式检索问答：先发一次引用来源，再逐 token 发答案，最后发完成；出错走 onError。
     * 多轮记忆只在完整答案拿到后存一次，绝不在每个 token 到达时存。
     * 无命中时直接发固定回答，不调模型；流式 token 是边收边发的，引用清洗只对存进记忆的完整答案做。
     */
    public void askStream(AskRequest request, AskScope scope, StreamListener listener) {
        PreparedAsk prepared = prepare(request, scope);
        listener.onSources(prepared.hits());
        if (prepared.hits().isEmpty()) {
            listener.onToken(NOT_FOUND_ANSWER);
            saveMemory(prepared, request.getQuestion(), NOT_FOUND_ANSWER);
            listener.onComplete();
            return;
        }
        StringBuilder answerBuilder = new StringBuilder();
        streamingChatModel.chat(prepared.messages(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                answerBuilder.append(partialResponse);
                listener.onToken(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                String answer = completeResponse.aiMessage().text();
                if (answer == null) {
                    answer = answerBuilder.toString();
                }
                saveMemory(prepared, request.getQuestion(), sanitizeCitations(answer, prepared.hits().size()));
                listener.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                listener.onError(error);
            }
        });
    }

    /**
     * 共用准备：问题向量化 → 检索 → 拼带编号的资料段与问题 → 取会话历史，拼成发给模型的消息列表。
     */
    private PreparedAsk prepare(AskRequest request, AskScope scope) {
        Response<Embedding> embeddingResponse = embeddingModel.embed(request.getQuestion());
        int maxResults = MAX_RESULTS;
        if (request.getMaxResults() != null) {
            maxResults = request.getMaxResults();
        }
        double minScore = MIN_SCORE;
        if (request.getMinScore() != null) {
            minScore = request.getMinScore();
        }
        // 权限过滤进 pgvector：只查全员可见 + 本部门的块，没权限的块连 prompt 都不进；只查已发布的块
        String department = scope.department();
        Filter filter = department == null
                ? MetadataFilterBuilder.metadataKey(MetadataKeys.VISIBILITY).isEqualTo(Visibility.ALL)
                : MetadataFilterBuilder.metadataKey(MetadataKeys.VISIBILITY).isIn(Visibility.ALL, department);
        filter = filter.and(MetadataFilterBuilder.metadataKey(MetadataKeys.STATUS).isEqualTo("approved"));
        if (request.getSources() != null && !request.getSources().isEmpty()) {
            filter = filter.and(MetadataFilterBuilder.metadataKey(MetadataKeys.SOURCE)
                    .isIn(request.getSources().toArray(new String[0])));
        }
        // 混合检索：向量路 + 关键词路 RRF 融合；老块没有 status 字段时须先跑回填 SQL（见 docs/architecture.md），否则查不到
        List<HybridSearchService.RetrievedChunk> chunks = hybridSearchService.search(
                embeddingResponse.content(), filter, request.getQuestion(), department,
                request.getSources(), maxResults, minScore);

        Set<String> distinctSources = new LinkedHashSet<>();
        List<ReferenceHit> hits = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            HybridSearchService.RetrievedChunk chunk = chunks.get(i);
            contextBuilder.append("[").append(i + 1).append("] ").append(chunk.text()).append("\n\n");
            distinctSources.add(chunk.source());
            ReferenceHit hit = new ReferenceHit();
            hit.setIndex(i + 1);
            hit.setScore(chunk.score());
            hit.setSource(chunk.source());
            hit.setText(chunk.text());
            hits.add(hit);
        }
        String prompt = "资料：\n" + contextBuilder + "问题：" + request.getQuestion();

        ChatMemory chatMemory = null;
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            // 会话键带用户名 + 部门：不同用户、不同部门各用各的历史；历史存在 chat_memory 表里，重启不丢，24 小时未更新由定时任务清理
            String memoryKey = scope.username() + "|" + request.getSessionId() + "|"
                    + (scope.department() == null ? "all-only" : scope.department());
            chatMemory = MessageWindowChatMemory.builder()
                    .id(memoryKey)
                    .maxMessages(MAX_MEMORY_MESSAGES)
                    .chatMemoryStore(chatMemoryStore)
                    .build();
        }
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = SYSTEM_PROMPT;
        if (!chunks.isEmpty()) {
            // 引用编号范围写死在提示词里：本次几块资料就只能引用 [1] 到 [N]，模型编不出 [N+1]
            systemPrompt += "本次共提供 " + chunks.size() + " 块资料，引用编号只能是 [1] 到 [" + chunks.size()
                    + "]，不许编造编号；每句有事实内容的回答都要带引用。";
        }
        messages.add(SystemMessage.from(systemPrompt));
        if (chatMemory != null) {
            messages.addAll(chatMemory.messages());
        }
        messages.add(UserMessage.from(prompt));
        return new PreparedAsk(messages, chatMemory, hits, new ArrayList<>(distinctSources));
    }

    /** 历史里只存问答原文，不含本轮资料段，避免上下文重复膨胀。 */
    private void saveMemory(PreparedAsk prepared, String question, String answer) {
        if (prepared.chatMemory() != null) {
            prepared.chatMemory().add(UserMessage.from(question));
            prepared.chatMemory().add(AiMessage.from(answer));
        }
    }
}
