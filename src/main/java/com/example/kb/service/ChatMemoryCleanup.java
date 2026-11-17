package com.example.kb.service;

import com.example.kb.security.OidcCodeStore;
import com.example.kb.security.RefreshTokenStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每小时清理一次 24 小时未更新的会话记忆，避免 chat_memory 表无限增长；同时清理过期的限流窗口、Refresh Token、OIDC 换码与 7 天前的入库任务。 */
@Component
public class ChatMemoryCleanup {

    private final JdbcChatMemoryStore chatMemoryStore;
    private final JdbcTemplate jdbcTemplate;
    private final RefreshTokenStore refreshTokenStore;
    private final OidcCodeStore oidcCodeStore;
    private final IngestJobStore ingestJobStore;

    public ChatMemoryCleanup(JdbcChatMemoryStore chatMemoryStore, JdbcTemplate jdbcTemplate,
                             RefreshTokenStore refreshTokenStore, OidcCodeStore oidcCodeStore,
                             IngestJobStore ingestJobStore) {
        this.chatMemoryStore = chatMemoryStore;
        this.jdbcTemplate = jdbcTemplate;
        this.refreshTokenStore = refreshTokenStore;
        this.oidcCodeStore = oidcCodeStore;
        this.ingestJobStore = ingestJobStore;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanExpiredSessions() {
        chatMemoryStore.deleteExpired();
        jdbcTemplate.update("DELETE FROM rate_limit WHERE window_start < now() - interval '1 hour'");
        refreshTokenStore.deleteExpired();
        oidcCodeStore.deleteExpired();
        ingestJobStore.deleteOld();
    }
}
