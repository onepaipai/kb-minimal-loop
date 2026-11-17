package com.example.kb.security;

import java.time.Instant;
import java.util.Optional;

/** OIDC 一次性换码：登录回调只发一个 5 分钟有效的码，前端凭码换 Token，码本身不进 URL 之外的地方。 */
public interface OidcCodeStore {

    record OidcCodeRow(String codeHash, String username, Instant expiresAt, boolean used) {
    }

    void save(String codeHash, String username, Instant expiresAt);

    Optional<OidcCodeRow> find(String codeHash);

    void markUsed(String codeHash);

    void deleteExpired();
}
