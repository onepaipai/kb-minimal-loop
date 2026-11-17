package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 审计日志：谁、在什么时候、对什么做了什么（登录、上传、删除、审批、建用户、改密码、问答）。
 * 只增不改；记录失败绝不影响主流程（记不进去只打一条 WARN）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    /** 详情截断长度：问答原文只记前 100 字，避免审计表被长问题撑大 */
    private static final int MAX_DETAIL_LENGTH = 100;

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AuditEntry(String username, String action, String target, String detail, OffsetDateTime createdAt) {
    }

    public void record(String username, String action, String target, String detail) {
        try {
            String cleanDetail = detail == null ? null
                    : detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail;
            jdbcTemplate.update("INSERT INTO audit_log(id, username, action, target, detail) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), username, action, target, cleanDetail);
        } catch (Exception e) {
            // 审计写不进去不能让登录/上传跟着失败，只记日志
            log.warn("审计日志写入失败 action={} user={}", action, username, e);
        }
    }

    public List<AuditEntry> listLogs(int page, int size) {
        return jdbcTemplate.query(
                "SELECT username, action, target, detail, created_at FROM audit_log "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new AuditEntry(rs.getString("username"), rs.getString("action"),
                        rs.getString("target"), rs.getString("detail"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                size, page * size);
    }

    public int countLogs() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
        return count == null ? 0 : count;
    }
}
