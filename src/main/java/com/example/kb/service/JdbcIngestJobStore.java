package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 异步任务 JDBC 存储：输入持久化、租约领取和失败重试均由数据库保证。 */
@Service
public class JdbcIngestJobStore implements IngestJobStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIngestJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IngestJob create(String source, String username, String visibility) {
        return createWithPayload(source, username, visibility, false, "pending", null);
    }

    @Override
    public IngestJob createWithPayload(String source, String username, String visibility, boolean javaFile,
                                       String targetStatus, byte[] bytes) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ingest_job(id, source, username, visibility, status, is_java, "
                        + "target_status, payload) VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                id, source, username, visibility, javaFile, targetStatus, bytes);
        return find(id).orElseThrow(() -> new IllegalStateException("创建入库任务后查询失败"));
    }

    @Override
    public Optional<IngestJob> find(UUID id) {
        try {
            IngestJob job = jdbcTemplate.queryForObject(
                    "SELECT id, source, username, visibility, status, chunks, error, created_at "
                            + "FROM ingest_job WHERE id = ?",
                    (rs, rowNum) -> new IngestJob(UUID.fromString(rs.getString("id")), rs.getString("source"),
                            rs.getString("username"), rs.getString("visibility"), rs.getString("status"),
                            rs.getInt("chunks"), rs.getString("error"),
                            rs.getObject("created_at", OffsetDateTime.class)), id);
            return Optional.ofNullable(job);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<JobInput> loadInput(UUID id) {
        try {
            JobInput input = jdbcTemplate.queryForObject(
                    "SELECT payload, source, is_java, visibility, target_status FROM ingest_job WHERE id = ?",
                    (rs, rowNum) -> new JobInput(rs.getBytes("payload"), rs.getString("source"),
                            rs.getBoolean("is_java"), rs.getString("visibility"), rs.getString("target_status")), id);
            if (input == null || input.bytes() == null) {
                return Optional.empty();
            }
            return Optional.of(input);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean claim(UUID id, String owner, long leaseSeconds) {
        return jdbcTemplate.update("UPDATE ingest_job SET status = 'RUNNING', lease_owner = ?, "
                        + "lease_until = now() + (?::text || ' seconds')::interval, "
                        + "attempt_count = attempt_count + 1, updated_at = now() WHERE id = ? "
                        + "AND payload IS NOT NULL AND (status = 'PENDING' OR "
                        + "(status = 'RUNNING' AND lease_until < now()))", owner, leaseSeconds, id) > 0;
    }

    @Override
    public List<UUID> findRecoverable() {
        return jdbcTemplate.query("SELECT id FROM ingest_job WHERE payload IS NOT NULL AND "
                        + "(status = 'PENDING' OR (status = 'RUNNING' AND lease_until < now())) "
                        + "ORDER BY created_at LIMIT 20",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)));
    }

    @Override
    public void updateStatus(UUID id, String status, Integer chunks, String error) {
        jdbcTemplate.update("UPDATE ingest_job SET status = ?, chunks = COALESCE(?, chunks), error = ?, "
                        + "lease_owner = NULL, lease_until = NULL, updated_at = now() WHERE id = ?",
                status, chunks, error, id);
    }

    @Override
    public void deleteOld() {
        jdbcTemplate.update("DELETE FROM ingest_job WHERE created_at < now() - interval '7 days'");
    }
}
