package com.example.kb.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OIDC 一次性换码：签发、单次使用、过期、未知码都不碰数据库，用内存假 Store 测逻辑。 */
class OidcCodeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T11:00:00Z");

    static class InMemoryStore implements OidcCodeStore {
        final Map<String, OidcCodeRow> rows = new HashMap<>();

        @Override
        public void save(String codeHash, String username, Instant expiresAt) {
            rows.put(codeHash, new OidcCodeRow(codeHash, username, expiresAt, false));
        }

        @Override
        public Optional<OidcCodeRow> find(String codeHash) {
            return Optional.ofNullable(rows.get(codeHash));
        }

        @Override
        public void markUsed(String codeHash) {
            rows.computeIfPresent(codeHash,
                    (k, v) -> new OidcCodeRow(v.codeHash(), v.username(), v.expiresAt(), true));
        }

        @Override
        public void deleteExpired() {
            rows.values().removeIf(v -> v.used() || !v.expiresAt().isAfter(NOW));
        }
    }

    private static OidcCodeService service(InMemoryStore store, Instant now) {
        return new OidcCodeService(store, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issueAndConsumeOnce() {
        InMemoryStore store = new InMemoryStore();
        OidcCodeService service = service(store, NOW);
        String code = service.issue("zhangsan");
        assertEquals("zhangsan", service.consume(code).orElseThrow());
        assertTrue(service.consume(code).isEmpty(), "换码只能用一次");
    }

    @Test
    void consumeFailsAfterExpiry() {
        InMemoryStore store = new InMemoryStore();
        String code = service(store, NOW).issue("zhangsan");
        assertTrue(service(store, NOW.plus(Duration.ofMinutes(6))).consume(code).isEmpty());
    }

    @Test
    void consumeFailsForUnknownOrBlankCode() {
        OidcCodeService service = service(new InMemoryStore(), NOW);
        assertTrue(service.consume("no-such-code").isEmpty());
        assertTrue(service.consume("").isEmpty());
        assertTrue(service.consume(null).isEmpty());
    }

    @Test
    void twoIssuesGiveDifferentCodes() {
        OidcCodeService service = service(new InMemoryStore(), NOW);
        assertNotEquals(service.issue("zhangsan"), service.issue("zhangsan"));
    }
}
