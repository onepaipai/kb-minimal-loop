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
 * 鉴权见 ApiKeyAuthFilter：只对非管理接口（/api/admin 与 /api/auth 除外）生效，权限就是 Key 主人的角色。
 */
@Service
public class ApiKeyService {

    /** Key 前缀：日志与请求头里一眼认出这是 API Key 不是 JWT */
    public static final String KEY_PREFIX = "kb_";
    private static final String KEY_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int KEY_RANDOM_LENGTH = 32;

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ApiKeyItem(UUID id, String name, String username, boolean revoked,
                             Instant lastUsedAt, Instant createdAt) {
    }

    public record CreatedKey(UUID id, String rawKey) {
    }

    /** 建 Key：返回的明文只出现这一次，前端展示后即丢，库里只有哈希。 */
    public CreatedKey create(String name, String username) {
        String rawKey = generateRawKey();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO api_key (id, key_hash, name, username) VALUES (?, ?, ?, ?)",
                id, sha256Hex(rawKey), name, username);
        return new CreatedKey(id, rawKey);
    }

    /** 全部 Key 列表（管理员看）：不含明文。 */
    public List<ApiKeyItem> list() {
        return jdbcTemplate.query(
                "SELECT id, name, username, revoked, last_used_at, created_at FROM api_key "
                        + "ORDER BY created_at DESC",
                (rs, rowNum) -> new ApiKeyItem(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("name"),
                        rs.getString("username"),
                        rs.getBoolean("revoked"),
                        rs.getTimestamp("last_used_at") == null
                                ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 吊销：返回是否改到（不存在返回 false）。 */
    public boolean revoke(UUID id) {
        return jdbcTemplate.update(
                "UPDATE api_key SET revoked = TRUE WHERE id = ? AND revoked = FALSE", id) > 0;
    }

    /**
     * 校验 Key：哈希对上且未吊销，返回主人的身份，并更新最后使用时间；
     * 对不上、已吊销、主人已被删时返回空。
     */
    public Optional<UserPrincipal> validate(String rawKey) {
        List<UserPrincipal> principals = jdbcTemplate.query(
                "SELECT u.username, u.role, u.department FROM api_key k "
                        + "JOIN app_user u ON u.username = k.username "
                        + "WHERE k.key_hash = ? AND k.revoked = FALSE",
                (rs, rowNum) -> new UserPrincipal(
                        rs.getString("username"), rs.getString("role"), rs.getString("department")),
                sha256Hex(rawKey));
        if (principals.isEmpty()) {
            return Optional.empty();
        }
        jdbcTemplate.update("UPDATE api_key SET last_used_at = now() WHERE key_hash = ?", sha256Hex(rawKey));
        return Optional.of(principals.get(0));
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
