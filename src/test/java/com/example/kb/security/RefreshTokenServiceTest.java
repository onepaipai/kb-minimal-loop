package com.example.kb.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Refresh Token：签发、轮换、过期、吊销都不碰数据库，用内存假 Store 测逻辑。 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    static class InMemoryStore implements RefreshTokenStore {
        final Map<String, RefreshTokenRow> rows = new HashMap<>();

        @Override
        public void save(String tokenHash, String username, Instant expiresAt) {
            rows.put(tokenHash, new RefreshTokenRow(tokenHash, username, expiresAt, false));
        }

        @Override
        public Optional<RefreshTokenRow> find(String tokenHash) {
            return Optional.ofNullable(rows.get(tokenHash));
        }

        @Override
        public void revoke(String tokenHash) {
            rows.computeIfPresent(tokenHash,
                    (k, v) -> new RefreshTokenRow(v.tokenHash(), v.username(), v.expiresAt(), true));
        }

        @Override
        public void revokeAll(String username) {
            rows.replaceAll((k, v) -> v.username().equals(username)
                    ? new RefreshTokenRow(v.tokenHash(), v.username(), v.expiresAt(), true) : v);
        }

        @Override
        public void deleteExpired() {
            rows.values().removeIf(v -> v.revoked() || !v.expiresAt().isAfter(NOW));
        }
    }

    private static RefreshTokenService service(InMemoryStore store, Instant now) {
        return new RefreshTokenService(store, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String sha256Hex(String raw) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void issueStoresSha256HashWithSevenDayExpiry() throws Exception {
        InMemoryStore store = new InMemoryStore();
        String raw = service(store, NOW).issue("alice");
        RefreshTokenStore.RefreshTokenRow row = store.find(sha256Hex(raw)).orElseThrow();
        assertEquals("alice", row.username());
        assertEquals(NOW.plus(Duration.ofDays(7)), row.expiresAt());
    }

    @Test
    void refreshRotatesOldTokenAway() {
        InMemoryStore store = new InMemoryStore();
        RefreshTokenService service = service(store, NOW);
        String oldToken = service.issue("alice");
        RefreshTokenService.RefreshResult result = service.refresh(oldToken).orElseThrow();
        assertEquals("alice", result.username());
        assertNotEquals(oldToken, result.refreshToken());
        assertTrue(service.refresh(oldToken).isEmpty(), "轮换出的旧 Token 只能用一次");
        assertTrue(service.refresh(result.refreshToken()).isPresent(), "新 Token 能继续刷新");
    }

    @Test
    void refreshFailsAfterExpiry() {
        InMemoryStore store = new InMemoryStore();
        String token = service(store, NOW).issue("alice");
        assertTrue(service(store, NOW.plus(Duration.ofDays(8))).refresh(token).isEmpty());
    }

    @Test
    void refreshFailsForUnknownToken() {
        assertTrue(service(new InMemoryStore(), NOW).refresh("no-such-token").isEmpty());
    }

    @Test
    void revokeMakesTokenUnusable() {
        InMemoryStore store = new InMemoryStore();
        RefreshTokenService service = service(store, NOW);
        String token = service.issue("alice");
        service.revoke(token);
        assertTrue(service.refresh(token).isEmpty());
    }

    @Test
    void revokeAllOnlyAffectsThatUser() {
        InMemoryStore store = new InMemoryStore();
        RefreshTokenService service = service(store, NOW);
        String aliceToken = service.issue("alice");
        String bobToken = service.issue("bob");
        service.revokeAllForUser("alice");
        assertTrue(service.refresh(aliceToken).isEmpty());
        assertTrue(service.refresh(bobToken).isPresent());
    }

    @Test
    void twoIssuesGiveDifferentTokens() {
        RefreshTokenService service = service(new InMemoryStore(), NOW);
        assertNotEquals(service.issue("alice"), service.issue("alice"));
    }
}
