package com.example.kb.security;

import java.time.Instant;
import java.util.Optional;

/** Refresh Token 的存储口：库里只存哈希，不存明文。 */
public interface RefreshTokenStore {

    record RefreshTokenRow(String tokenHash, String username, Instant expiresAt, boolean revoked) {
    }

    void save(String tokenHash, String username, Instant expiresAt);

    Optional<RefreshTokenRow> find(String tokenHash);

    /** 原子消费一个仍有效的 Refresh Token；并发请求只能有一个成功。 */
    default Optional<RefreshTokenRow> consume(String tokenHash, Instant now) {
        Optional<RefreshTokenRow> row = find(tokenHash);
        if (row.isEmpty() || row.get().revoked() || !row.get().expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        revoke(tokenHash);
        return Optional.of(row.get());
    }

    void revoke(String tokenHash);

    void revokeAll(String username);

    /** 删掉已过期与已吊销的行，吊销过的 Token 查不到即视为无效。 */
    void deleteExpired();
}
