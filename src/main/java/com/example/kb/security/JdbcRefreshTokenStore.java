package com.example.kb.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Refresh Token 的 JDBC 实现：refresh_token 表。 */
@Service
public class JdbcRefreshTokenStore implements RefreshTokenStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(String tokenHash, String username, Instant expiresAt) {
        jdbcTemplate.update("INSERT INTO refresh_token (token_hash, username, expires_at) VALUES (?, ?, ?)",
                tokenHash, username, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<RefreshTokenRow> find(String tokenHash) {
        List<RefreshTokenRow> rows = jdbcTemplate.query(
                "SELECT token_hash, username, expires_at, revoked FROM refresh_token WHERE token_hash = ?",
                (rs, i) -> new RefreshTokenRow(rs.getString("token_hash"), rs.getString("username"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("revoked")),
                tokenHash);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<RefreshTokenRow> consume(String tokenHash, Instant now) {
        List<RefreshTokenRow> rows = jdbcTemplate.query(
                "UPDATE refresh_token SET revoked = TRUE WHERE token_hash = ? AND revoked = FALSE "
                        + "AND expires_at > ? RETURNING token_hash, username, expires_at, revoked",
                (rs, i) -> new RefreshTokenRow(rs.getString("token_hash"), rs.getString("username"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("revoked")),
                tokenHash, Timestamp.from(now));
        return rows.stream().findFirst();
    }

    @Override
    public void revoke(String tokenHash) {
        jdbcTemplate.update("UPDATE refresh_token SET revoked = TRUE WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void revokeAll(String username) {
        jdbcTemplate.update("UPDATE refresh_token SET revoked = TRUE WHERE username = ?", username);
    }

    @Override
    public void deleteExpired() {
        jdbcTemplate.update("DELETE FROM refresh_token WHERE expires_at < now() OR revoked = TRUE");
    }
}
