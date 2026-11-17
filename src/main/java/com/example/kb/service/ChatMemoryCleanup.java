package com.example.kb.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每小时清理一次 24 小时未更新的会话记忆，避免 chat_memory 表无限增长；同时清理过期的限流窗口。 */
@Component
public class ChatMemoryCleanup {

    private final JdbcChatMemoryStore chatMemoryStore;
    private final JdbcTemplate jdbcTemplate;

    public ChatMemoryCleanup(JdbcChatMemoryStore chatMemoryStore, JdbcTemplate jdbcTemplate) {
        this.chatMemoryStore = chatMemoryStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanExpiredSessions() {
        chatMemoryStore.deleteExpired();
        jdbcTemplate.update("DELETE FROM rate_limit WHERE window_start < now() - interval '1 hour'");
    }
}
