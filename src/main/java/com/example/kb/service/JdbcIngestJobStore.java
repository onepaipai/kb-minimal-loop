package com.example.kb.service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcIngestJobStore implements IngestJobStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIngestJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IngestJob create(String source, String username, String visibility) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO ingest_job(id, source, username, visibility, status) VALUES (?, ?, ?, ?, 'PENDING')",
                id, source, username, visibility);
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
                            rs.getObject("created_at", OffsetDateTime.class)),
                    id);
            return Optional.ofNullable(job);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateStatus(UUID id, String status, Integer chunks, String error) {
        jdbcTemplate.update("UPDATE ingest_job SET status = ?, chunks = COALESCE(?, chunks), error = ?, "
                        + "updated_at = now() WHERE id = ?",
                status, chunks, error, id);
    }

    @Override
    public void deleteOld() {
        jdbcTemplate.update("DELETE FROM ingest_job WHERE created_at < now() - interval '7 days'");
    }
}
