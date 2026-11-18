package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.kb.security.UserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 对外 API Key：给别的系统调问答/入库接口用，不用账号密码登录。
 * Key 形如 kb_ + 32 位随机串，库里只存 SHA-256 哈希，明文只在创建时返回一次；吊销后失效。
 * scope：all（除管理接口外都能调，等同于 Key 主人的角色）或 ask（只能问答与看清单）；
 * expires_at 为空表示永不过期，到了时间 validate 直接拒绝。鉴权见 ApiKeyAuthFilter。
 */
@Service
public class ApiKeyService {

    /** Key 前缀：日志与请求头里一眼认出这是 API Key 不是 JWT */
    public static final String KEY_PREFIX = "kb_";
    /** 权限范围：全部（默认）与仅问答 */
    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_ASK = "ask";
    /** 有效期上限（天）：超过这个数创建时报 400 */
    public static final int MAX_EXPIRES_DAYS = 3650;

    private static final String KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_RANDOM_LENGTH = 32;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ApiKeyItem(UUID id, String name, String username, boolean revoked,
                             Instant lastUsedAt, Instant createdAt, String scope, Instant expiresAt) {
    }

    /** 校验结果：Key 主人的身份 + 这个 Key 的权限范围（过滤器按范围拦接口）。 */
    public record ValidatedKey(UserPrincipal principal, String scope) {
    }

    public record CreatedKey(UUID id, String rawKey) {
    }

    /** 建 Key：返回的明文只出现这一次，前端展示后即丢，库里只有哈希；scope 非法或有效期越界抛 IllegalArgumentException。 */
    public CreatedKey create(String name, String username, String scope, Integer expiresInDays) {
        String effectiveScope = scope == null || scope.isBlank() ? SCOPE_ALL : scope.trim().toLowerCase();
        if (!SCOPE_ALL.equals(effectiveScope) && !SCOPE_ASK.equals(effectiveScope)) {
            throw new IllegalArgumentException("scope 只能是 all 或 ask");
        }
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_DAYS)) {
            throw new IllegalArgumentException("有效期 1-" + MAX_EXPIRES_DAYS + " 天");
        }
        String rawKey = generateRawKey();
        UUID id = UUID.randomUUID();
        Instant expiresAt = expiresInDays == null ? null : Instant.now().plusSeconds((long) expiresInDays * 86400);
        jdbcTemplate.update("INSERT INTO api_key (id, key_hash, name, username, scope, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, sha256Hex(rawKey), name, username, effectiveScope,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
        return new CreatedKey(id, rawKey);
    }

    /** 全部 Key 列表（管理员看）：不含明文。 */
    public List<ApiKeyItem> list() {
        return jdbcTemplate.query(
                "SELECT id, name, username, revoked, last_used_at, created_at, scope, expires_at FROM api_key "
                        + "ORDER BY created_at DESC",
                (rs, rowNum) -> new ApiKeyItem(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("name"),
                        rs.getString("username"),
                        rs.getBoolean("revoked"),
                        rs.getTimestamp("last_used_at") == null
                                ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("scope"),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toInstant()));
    }

    /** 吊销：返回是否改到（不存在返回 false）。 */
    public boolean revoke(UUID id) {
        return jdbcTemplate.update(
                "UPDATE api_key SET revoked = TRUE WHERE id = ? AND revoked = FALSE", id) > 0;
    }

    /**
     * 校验 Key：哈希对上、未吊销、未过期，返回主人的身份与权限范围，并更新最后使用时间；
     * 对不上、已吊销、已过期、主人已被删时返回空。
     */
    public Optional<ValidatedKey> validate(String rawKey) {
        List<ValidatedKey> keys = jdbcTemplate.query(
                "SELECT u.username, u.role, u.department, k.scope FROM api_key k "
                        + "JOIN app_user u ON u.username = k.username "
                        + "WHERE k.key_hash = ? AND k.revoked = FALSE "
                        + "AND (k.expires_at IS NULL OR k.expires_at > now())",
                (rs, rowNum) -> new ValidatedKey(
                        new UserPrincipal(rs.getString("username"), rs.getString("role"),
                                rs.getString("department")),
                        rs.getString("scope")),
                sha256Hex(rawKey));
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("UPDATE api_key SET last_used_at = now() WHERE key_hash = ?", sha256Hex(rawKey));
        return Optional.of(keys.get(0));
    }

    private String generateRawKey() {
        StringBuilder builder = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < KEY_RANDOM_LENGTH; i++) {
            builder.append(KEY_ALPHABET.charAt(random.nextInt(KEY_ALPHABET.length())));
        }
        return builder.toString();
    }

    /** SHA-256 哈希转小写十六进制（与 Refresh Token 的存法一致，只存哈希不存明文）。 */
    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
