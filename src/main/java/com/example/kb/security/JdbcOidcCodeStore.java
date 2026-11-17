package com.example.kb.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcOidcCodeStore implements OidcCodeStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOidcCodeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(String codeHash, String username, Instant expiresAt) {
        jdbcTemplate.update("INSERT INTO oidc_code(code_hash, username, expires_at) VALUES (?, ?, ?)",
                codeHash, username, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<OidcCodeRow> find(String codeHash) {
        try {
            OidcCodeRow row = jdbcTemplate.queryForObject(
                    "SELECT code_hash, username, expires_at, used FROM oidc_code WHERE code_hash = ?",
                    (rs, rowNum) -> new OidcCodeRow(rs.getString("code_hash"), rs.getString("username"),
                            rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("used")),
                    codeHash);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void markUsed(String codeHash) {
        jdbcTemplate.update("UPDATE oidc_code SET used = TRUE WHERE code_hash = ?", codeHash);
    }

    @Override
    public void deleteExpired() {
        jdbcTemplate.update("DELETE FROM oidc_code WHERE used = TRUE OR expires_at <= now()");
    }
}
