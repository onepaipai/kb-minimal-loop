package com.example.kb.service;

import com.example.kb.security.OidcCodeStore;
import com.example.kb.security.RefreshTokenStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每小时清理一次 24 小时未更新的会话记忆，避免 chat_memory 表无限增长；同时清理过期的限流窗口、Refresh Token、OIDC 换码、7 天前的入库任务与只保留最新 3 个的旧版块。 */
@Component
public class ChatMemoryCleanup {

    /** 每篇文档保留的 superseded 旧版数（再早的每小时清理掉，回滚只认保留的版本） */
    static final int KEEP_SUPERSEDED_VERSIONS = 3;

    private final JdbcChatMemoryStore chatMemoryStore;
    private final JdbcTemplate jdbcTemplate;
    private final RefreshTokenStore refreshTokenStore;
    private final OidcCodeStore oidcCodeStore;
    private final IngestJobStore ingestJobStore;
    private final DocumentManageService documentManageService;

    public ChatMemoryCleanup(JdbcChatMemoryStore chatMemoryStore, JdbcTemplate jdbcTemplate,
                             RefreshTokenStore refreshTokenStore, OidcCodeStore oidcCodeStore,
                             IngestJobStore ingestJobStore, DocumentManageService documentManageService) {
        this.chatMemoryStore = chatMemoryStore;
        this.jdbcTemplate = jdbcTemplate;
        this.refreshTokenStore = refreshTokenStore;
        this.oidcCodeStore = oidcCodeStore;
        this.ingestJobStore = ingestJobStore;
        this.documentManageService = documentManageService;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cleanExpiredSessions() {
        chatMemoryStore.deleteExpired();
        jdbcTemplate.update("DELETE FROM rate_limit WHERE window_start < now() - interval '1 hour'");
        refreshTokenStore.deleteExpired();
        oidcCodeStore.deleteExpired();
        ingestJobStore.deleteOld();
        documentManageService.purgeOldSuperseded(KEEP_SUPERSEDED_VERSIONS);
    }
}
