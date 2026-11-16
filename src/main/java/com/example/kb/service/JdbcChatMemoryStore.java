package com.example.kb.service;

import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 会话记忆的数据库存储：消息列表按 LangChain4j 的 JSON 编解码存进 chat_memory 表，重启不丢。 */
@Service
public class JdbcChatMemoryStore implements ChatMemoryStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcChatMemoryStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT messages::text FROM chat_memory WHERE id = ?", String.class, memoryId.toString());
            return json == null ? List.of() : ChatMessageDeserializer.messagesFromJson(json);
        } catch (EmptyResultDataAccessException e) {
            return List.of();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        jdbcTemplate.update("INSERT INTO chat_memory(id, messages, updated_at) VALUES (?, ?::jsonb, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET messages = EXCLUDED.messages, updated_at = now()",
                memoryId.toString(), ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbcTemplate.update("DELETE FROM chat_memory WHERE id = ?", memoryId.toString());
    }

    /** 删掉 24 小时未更新的会话，供定时清理调用，返回删除行数。 */
    public int deleteExpired() {
        return jdbcTemplate.update("DELETE FROM chat_memory WHERE updated_at < now() - interval '24 hours'");
    }
}
