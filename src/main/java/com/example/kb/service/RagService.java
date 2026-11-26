package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.kb.common.Visibility;
import com.example.kb.dto.AskRequest;
import com.example.kb.dto.AskResponse;
import com.example.kb.dto.ReferenceHit;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

    /** 每个会话最多保留的对话消息条数（约 3 轮）：少带历史，减少每轮发给模型的上下文与首字延迟 */
    private static final int MAX_MEMORY_MESSAGES = 6;
    /** 检索无命中时的固定回答：不调模型，保证每次都一样，也不给模型编造的机会 */
    static final String NOT_FOUND_ANSWER = "资料里没找到";

    /**
     * 问答的作用域：由 Controller 在请求线程里从登录用户解析好再传进来（流式问答跑在独立线程，拿不到请求线程的 SecurityContext，必须显式传）。
     * username 进会话键做隔离，department 是已按账号规则算好的生效部门。
     */
    public record AskScope(String username, String department) {
    }

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatMemoryStore chatMemoryStore;
    private final HybridSearchService hybridSearchService;
    private final FaqService faqService;
    private final QuestionMissService questionMissService;
    private final ChatSessionService chatSessionService;
    private final QueryEmbeddingCache queryEmbeddingCache;

    public RagService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                      ChatModel chatModel, StreamingChatModel streamingChatModel, ChatMemoryStore chatMemoryStore,
                      HybridSearchService hybridSearchService, FaqService faqService,
                      QuestionMissService questionMissService, ChatSessionService chatSessionService) {
        this.embeddingStore = embeddingStore;
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.chatMemoryStore = chatMemoryStore;
        this.hybridSearchService = hybridSearchService;
        this.faqService = faqService;
        this.questionMissService = questionMissService;
        this.chatSessionService = chatSessionService;
        this.queryEmbeddingCache = new QueryEmbeddingCache(embeddingModel);
    }

    /** 流式问答的事件回调：Controller 接到 SseEmitter 上，顺序为 sources → token* → done / onError。 */
    public interface StreamListener {
        void onSources(List<ReferenceHit> hits);
        void onToken(String token);
        void onComplete();
        void onError(Throwable error);
    }

    /**
     * ask() 与 askStream() 共用的准备结果：拼好的消息列表、会话记忆（无 sessionId 时为 null）、引用。
     * standardAnswer 非 null 表示命中了标准答案：messages 为 null，不检索不调模型，直接返回标准答案。
     */
    private record PreparedAsk(List<ChatMessage> messages, ChatMemory chatMemory,
                               List<ReferenceHit> hits, List<String> sources, String standardAnswer) {
    }

    /**
     * 检索并回答：把检索到的块拼成带编号的资料段连同问题一起发给模型。
     * 命中标准答案时不检索不调模型，直接返回；无命中时不调模型，直接回“资料里没找到”；
     * 有命中时先删超出引用范围的编号，再校验每个引用有没有文本依据。
     */
    public AskResponse ask(AskRequest request, AskScope scope) {
        chatSessionService.appendQuestion(scope.username(), request.getSessionId(), scope.department(),
                request.getQuestion());
        PreparedAsk prepared = prepare(request, scope);
        String answer;
        if (prepared.standardAnswer() != null) {
            answer = prepared.standardAnswer();
        } else if (prepared.hits().isEmpty()) {
            answer = NOT_FOUND_ANSWER;
        } else {
            answer = CitationValidator.validate(
                    sanitizeCitations(chatModel.chat(prepared.messages()).aiMessage().text(),
                            prepared.hits().size()),
                    RagAnswerHelper.chunkTexts(prepared.hits()));
        }
        saveMemory(prepared, request.getQuestion(), answer);
        recordMissIfNeeded(answer, scope, request.getQuestion());
        chatSessionService.completeAnswer(scope.username(), request.getSessionId(), answer, prepared.hits());

        AskResponse response = new AskResponse();
        response.setAnswer(answer);
        response.setSources(prepared.sources());
        response.setHits(prepared.hits());
        return response;
    }

    /** 删掉答案里超出本次引用范围的编号（如只有 3 块却写了 [9]），防止模型编造引用；范围内的不动。 */
    static String sanitizeCitations(String answer, int hitCount) {
        return RagAnswerHelper.sanitizeCitations(answer, hitCount);
    }

    /**
     * 流式检索问答：先发一次引用来源，再逐 token 发答案，最后发完成；出错走 onError。
     * 多轮记忆只在完整答案拿到后存一次，绝不在每个 token 到达时存。
     * 会话历史的用户提问在开头就落库（中途刷新也能看到），助手回答在完整答案拿到后补写；出错时保留已落库的提问。
     * 命中标准答案或无命中时直接发整段回答，不调模型；流式 token 边收边过引用过滤器，
     * 超范围的 [n] 发出去之前就删掉，存进记忆与会话历史的是同一份过滤后并校验过的文本。
     */
    public void askStream(AskRequest request, AskScope scope, StreamListener listener) {
        chatSessionService.appendQuestion(scope.username(), request.getSessionId(), scope.department(),
                request.getQuestion());
        PreparedAsk prepared = prepare(request, scope);
        listener.onSources(prepared.hits());
        if (prepared.standardAnswer() != null) {
            listener.onToken(prepared.standardAnswer());
            saveMemory(prepared, request.getQuestion(), prepared.standardAnswer());
            chatSessionService.completeAnswer(scope.username(), request.getSessionId(),
                    prepared.standardAnswer(), prepared.hits());
            listener.onComplete();
            return;
        }
        if (prepared.hits().isEmpty()) {
            listener.onToken(NOT_FOUND_ANSWER);
            saveMemory(prepared, request.getQuestion(), NOT_FOUND_ANSWER);
            recordMissIfNeeded(NOT_FOUND_ANSWER, scope, request.getQuestion());
            chatSessionService.completeAnswer(scope.username(), request.getSessionId(),
                    NOT_FOUND_ANSWER, prepared.hits());
            listener.onComplete();
            return;
        }
        StreamingCitationFilter citationFilter = new StreamingCitationFilter(prepared.hits().size());
        StringBuilder answerBuilder = new StringBuilder();
        streamingChatModel.chat(prepared.messages(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                String safe = citationFilter.feed(partialResponse);
                answerBuilder.append(safe);
                if (!safe.isEmpty()) {
                    listener.onToken(safe);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                String tail = citationFilter.flush();
                answerBuilder.append(tail);
                if (!tail.isEmpty()) {
                    listener.onToken(tail);
                }
                String answer = answerBuilder.toString();
                if (answer.isBlank() && completeResponse.aiMessage() != null
                        && completeResponse.aiMessage().text() != null) {
                    // 有的模型不发 partial，直接拿整段答案过一遍过滤器
                    answer = citationFilter.feed(completeResponse.aiMessage().text())
                            + citationFilter.flush();
                }
                String finalAnswer = CitationValidator.validate(
                        sanitizeCitations(answer, prepared.hits().size()), RagAnswerHelper.chunkTexts(prepared.hits()));
                saveMemory(prepared, request.getQuestion(), finalAnswer);
                recordMissIfNeeded(finalAnswer, scope, request.getQuestion());
                chatSessionService.completeAnswer(scope.username(), request.getSessionId(),
                        finalAnswer, prepared.hits());
                listener.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                listener.onError(error);
            }
        });
    }

    /**
     * 纯检索：FAQ 匹配或向量+关键词混合检索，只返回命中块，不调聊天模型。
     * 与问答 prepare 共用 retrieveChunks，避免权限/检索规则漂移；向量化与重排是检索必需，仍会调用。
     */
    public List<ReferenceHit> searchHits(AskRequest request, AskScope scope) {
        Optional<FaqService.FaqEntry> standard = faqService.match(request.getQuestion(), scope.department());
        if (standard.isPresent()) {
            return List.of(standardHit(standard.get().answer()));
        }
        List<HybridSearchService.RetrievedChunk> chunks = retrieveChunks(request, scope);
        return new RagPromptBuilder().build(chunks, request.getQuestion()).hits();
    }

    /** 标准答案命中：包装成一条引用块，搜索与问答共用。 */
    private ReferenceHit standardHit(String answer) {
        ReferenceHit hit = new ReferenceHit();
        hit.setIndex(1);
        hit.setScore(1.0);
        hit.setSource("标准答案");
        hit.setText(answer);
        return hit;
    }

    /**
     * 检索主干：问题向量化 → 权限 Filter → 向量+关键词混合检索（含 RRF 融合与神经重排）。
     * searchHits 与 prepare 共用，改权限或检索规则只改这一处。
     */
    private List<HybridSearchService.RetrievedChunk> retrieveChunks(AskRequest request, AskScope scope) {
        Embedding queryEmbedding = queryEmbeddingCache.embed(request.getQuestion());
        int maxResults = RagPromptBuilder.MAX_RESULTS;
        if (request.getMaxResults() != null) {
            maxResults = request.getMaxResults();
        }
        double minScore = RagPromptBuilder.MIN_SCORE;
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
        // 老块没有 status 字段时须先跑回填 SQL（见 docs/architecture.md），否则向量路查不到
        return hybridSearchService.search(queryEmbedding, filter, request.getQuestion(), department,
                request.getSources(), maxResults, minScore);
    }

    /**
     * 共用准备：先匹配标准答案（命中即短路，不检索不调模型）；未命中再走
     * 问题向量化 → 检索 → 拼带编号的资料段与问题 → 取会话历史，拼成发给模型的消息列表。
     */
    private PreparedAsk prepare(AskRequest request, AskScope scope) {
        Optional<FaqService.FaqEntry> standard = faqService.match(request.getQuestion(), scope.department());
        if (standard.isPresent()) {
            List<ReferenceHit> hits = new ArrayList<>();
            hits.add(standardHit(standard.get().answer()));
            List<String> sources = new ArrayList<>();
            sources.add("标准答案");
            return new PreparedAsk(null, buildChatMemory(request, scope), hits, sources, standard.get().answer());
        }
        List<HybridSearchService.RetrievedChunk> chunks = retrieveChunks(request, scope);
        RagPromptParts promptParts = new RagPromptBuilder().build(chunks, request.getQuestion());
        ChatMemory chatMemory = buildChatMemory(request, scope);
        List<ChatMessage> messages = promptParts.messages();
        if (chatMemory != null) {
            messages.addAll(1, chatMemory.messages());
        }
        return new PreparedAsk(messages, chatMemory, promptParts.hits(), promptParts.sources(), null);
    }

    /**
     * 会话记忆：sessionId 传入时按 用户名|sessionId|部门 组键取历史。
     * 会话键带用户名 + 部门：不同用户、不同部门各用各的历史；历史存在 chat_memory 表里，重启不丢，
     * 24 小时未更新由定时任务清理。标准答案路径与检索路径共用。
     */
    private ChatMemory buildChatMemory(AskRequest request, AskScope scope) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return null;
        }
        String memoryKey = scope.username() + "|" + request.getSessionId() + "|all-only";
        if (scope.department() != null) {
            memoryKey = scope.username() + "|" + request.getSessionId() + "|" + scope.department();
        }
        return MessageWindowChatMemory.builder()
                .id(memoryKey)
                .maxMessages(MAX_MEMORY_MESSAGES)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    /** 答案是“资料里没找到”就记一条知识盲区（检索无命中或模型明确说没有都算），记录失败在 service 内部吞掉。 */
    private void recordMissIfNeeded(String answer, AskScope scope, String question) {
        if (NOT_FOUND_ANSWER.equals(answer)) {
            questionMissService.record(scope.username(), scope.department(), question);
        }
    }

    /** 历史里只存问答原文，不含本轮资料段，避免上下文重复膨胀。 */
    private void saveMemory(PreparedAsk prepared, String question, String answer) {
        if (prepared.chatMemory() != null) {
            prepared.chatMemory().add(UserMessage.from(question));
            prepared.chatMemory().add(AiMessage.from(answer));
        }
    }
}
