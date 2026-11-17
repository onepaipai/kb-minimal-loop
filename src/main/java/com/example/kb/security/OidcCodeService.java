package com.example.kb.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** OIDC 一次性换码：随机码只发一次，库里只存 SHA-256 哈希，5 分钟有效，用过即废。 */
@Service
public class OidcCodeService {

    private static final Duration CODE_VALIDITY = Duration.ofMinutes(5);

    private final OidcCodeStore store;
    private final Clock clock;

    public OidcCodeService(OidcCodeStore store) {
        this(store, Clock.systemUTC());
    }

    OidcCodeService(OidcCodeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 发一个换码并返回明文（只返回这一次），调用方把它放进回调跳转地址。 */
    public String issue(String username) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(sha256Hex(code), username, clock.instant().plus(CODE_VALIDITY));
        return code;
    }

    /** 校验换码并返回对应用户名；不存在、已用、过期都返回空。 */
    public Optional<String> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return store.find(sha256Hex(code))
                .filter(row -> !row.used() && row.expiresAt().isAfter(clock.instant()))
                .map(row -> {
                    store.markUsed(row.codeHash());
                    return row.username();
                });
    }

    static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
