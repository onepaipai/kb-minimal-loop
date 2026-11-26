package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.example.kb.dto.ReferenceHit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话历史（UI 层）：问答与回答落库，跨设备同步；与 chat_memory（喂模型、24 小时清理）相互独立，长期保留。
 * 保存失败只打 WARN，不影响问答主流程（与审计日志同一策略）。
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);
    private static final int MAX_TITLE_LENGTH = 30;
    private static final int MAX_CONTENT_LENGTH = 8000;
    /** 会话清单上限：最近更新的 100 个 */
    private static final int LIST_LIMIT = 100;
    /** 单会话消息回放上限 */
    private static final int MESSAGE_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatSessionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 会话清单条目：会话号、标题（首问截断）、最近更新时间。 */
    public record SessionItem(String id, String title, OffsetDateTime updatedAt) {
    }

    /** 消息回放条目：user / assistant，assistant 带 refs（引用命中，JSONB 存回）。 */
    public record MessageItem(String role, String content, List<ReferenceHit> refs, OffsetDateTime createdAt) {
    }

    /**
     * 落一轮问答：会话不存在则建（标题取首问），存在只刷更新时间；先插用户消息再插回答，refs 存 JSONB。
     * sessionId 为空直接跳过（不建会话）。
     */
    public void append(String username, String sessionId, String department, String question,
                       String answer, List<ReferenceHit> hits) {
        appendQuestion(username, sessionId, department, question);
        completeAnswer(username, sessionId, answer, hits);
    }

    /**
     * 提问即落库：会话不存在则建（标题取首问），先插用户消息；回答生成完再调 completeAnswer 补上助手消息。
     * sessionId 为空直接跳过（不建会话）；保存失败只打 WARN，不影响问答主流程。
     */
    public void appendQuestion(String username, String sessionId, String department, String question) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String cleanSessionId = sessionId.trim();
        try {
            jdbcTemplate.update("INSERT INTO chat_session(username, id, title, department) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (username, id) DO UPDATE SET updated_at = now()",
                    username, cleanSessionId, truncate(question, MAX_TITLE_LENGTH), department);
            jdbcTemplate.update("INSERT INTO chat_message(id, username, session_id, role, content, refs) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), username, cleanSessionId, "user",
                    truncate(question, MAX_CONTENT_LENGTH), null);
        } catch (Exception e) {
            log.warn("会话提问保存失败 user={} session={}", username, cleanSessionId, e);
        }
    }

    /** 回答生成完成后补写助手消息（含 refs）；sessionId 为空或写库失败都不影响问答主流程。 */
    public void completeAnswer(String username, String sessionId, String answer, List<ReferenceHit> hits) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String cleanSessionId = sessionId.trim();
        try {
            jdbcTemplate.update("INSERT INTO chat_message(id, username, session_id, role, content, refs) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), username, cleanSessionId, "assistant",
                    truncate(answer, MAX_CONTENT_LENGTH), toJson(hits));
            jdbcTemplate.update("UPDATE chat_session SET updated_at = now() WHERE username = ? AND id = ?",
                    username, cleanSessionId);
        } catch (Exception e) {
            log.warn("会话回答保存失败 user={} session={}", username, cleanSessionId, e);
        }
    }

    /** 我的会话清单：最近更新在前。 */
    public List<SessionItem> list(String username) {
        return jdbcTemplate.query(
                "SELECT id, title, updated_at FROM chat_session WHERE username = ? "
                        + "ORDER BY updated_at DESC LIMIT " + LIST_LIMIT,
                (rs, rowNum) -> new SessionItem(rs.getString("id"), rs.getString("title"),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                username);
    }

    /** 某个会话的消息（只查自己的）：取最新的 200 条，按时间正序返回。 */
    public List<MessageItem> messages(String username, String sessionId) {
        List<MessageItem> messages = jdbcTemplate.query(
                "SELECT role, content, refs, created_at FROM chat_message "
                        + "WHERE username = ? AND session_id = ? ORDER BY created_at DESC LIMIT " + MESSAGE_LIMIT,
                (rs, rowNum) -> new MessageItem(rs.getString("role"), rs.getString("content"),
                        fromJson(rs.getString("refs")), rs.getObject("created_at", OffsetDateTime.class)),
                username, sessionId.trim());
        java.util.Collections.reverse(messages);
        return messages;
    }

    /** 删除会话与它的全部消息：返回会话是否存在。 */
    public boolean delete(String username, String sessionId) {
        int deleted = jdbcTemplate.update("DELETE FROM chat_session WHERE username = ? AND id = ?",
                username, sessionId.trim());
        jdbcTemplate.update("DELETE FROM chat_message WHERE username = ? AND session_id = ?",
                username, sessionId.trim());
        return deleted > 0;
    }

    /** 引用命中列表转 JSON 字符串（JSONB 入参），失败返回 null（消息照存，只是回看不带引用）。 */
    private String toJson(List<ReferenceHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(hits);
        } catch (Exception e) {
            log.warn("引用序列化失败，本条消息不存引用", e);
            return null;
        }
    }

    /** JSONB 字符串转回引用命中列表，失败返回 null。 */
    private List<ReferenceHit> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReferenceHit>>() {
            });
        } catch (Exception e) {
            log.warn("引用反序列化失败，本条消息回看不带引用", e);
            return null;
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() > maxLength) {
            return text.substring(0, maxLength);
        }
        return text;
    }
}
