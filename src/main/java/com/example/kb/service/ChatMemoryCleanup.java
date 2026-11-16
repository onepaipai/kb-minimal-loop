package com.example.kb.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每小时清理一次 24 小时未更新的会话记忆，避免 chat_memory 表无限增长。 */
@Component
public class ChatMemoryCleanup {

    private final JdbcChatMemoryStore chatMemoryStore;

    public ChatMemoryCleanup(JdbcChatMemoryStore chatMemoryStore) {
        this.chatMemoryStore = chatMemoryStore;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanExpiredSessions() {
        chatMemoryStore.deleteExpired();
    }
}
