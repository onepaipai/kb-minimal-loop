package com.example.kb.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Refresh Token：不透明随机串（32 字节），有效期 7 天；库里只存 SHA-256 哈希。
 * 每次刷新都轮换：旧 Token 立即吊销，换回一对新 Token；改密码时吊销该用户全部 Token。
 * Access Token（JWT，8 小时）过期后靠它续期，不用重新登录；它本身过期或被吊销才回登录页。
 */
@Service
public class RefreshTokenService {

    /** Refresh Token 有效期 7 天 */
    static final Duration REFRESH_VALIDITY = Duration.ofDays(7);

    private final RefreshTokenStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenStore store) {
        this(store, Clock.systemDefaultZone());
    }

    RefreshTokenService(RefreshTokenStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 刷新成功返回用户名与轮换出的新 Refresh Token；无效、已吊销、已过期返回空。 */
    public record RefreshResult(String username, String refreshToken) {
    }

    /** 签发一个 Refresh Token，返回明文（只返回这一次，调用方自己存好）。 */
    public String issue(String username) {
        String rawToken = generateRawToken();
        store.save(hash(rawToken), username, clock.instant().plus(REFRESH_VALIDITY));
        return rawToken;
    }

    /** 校验并轮换：旧 Token 吊销，返回新 Token；校验不过返回空。 */
    public Optional<RefreshResult> refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<RefreshTokenStore.RefreshTokenRow> row = store.consume(hash(rawToken), clock.instant());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RefreshResult(row.get().username(), issue(row.get().username())));
    }

    /** 吊销一个 Token；不存在或已吊销也不报错。 */
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        store.revoke(hash(rawToken));
    }

    /** 吊销某用户全部 Token（改密码后调用，该用户所有设备都要重新登录）。 */
    public void revokeAllForUser(String username) {
        store.revokeAll(username);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
