package com.example.kb.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.kb.common.Visibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 站内通知：待审批提醒发给管理员，通过/驳回发给上传人；只增不改，已读用 is_read 标记。
 * 通知失败不影响主流程：notifyPendingApproval / notifyDecision 内部 catch 住写库异常记 WARN 后吞掉，
 * 上传/审批本身已经成功，不能因为一条提醒发不出去就让用户看到 500。
 */
@Service
public class NotificationService {

    /** 通知类型：待审批 / 通过 / 驳回 */
    public static final String TYPE_PENDING = "PENDING_APPROVAL";
    public static final String TYPE_APPROVED = "APPROVED";
    public static final String TYPE_REJECTED = "REJECTED";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record NotificationItem(UUID id, String type, String title, String body, String relatedSource,
                                   boolean read, Instant createdAt) {
    }

    public void create(String username, String type, String title, String body, String relatedSource) {
        jdbcTemplate.update("INSERT INTO notification (id, username, type, title, body, related_source) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), username, type, title, body, relatedSource);
    }

    /** 待审批提醒：发给全部管理员；部门文档再发给该部门的部门管理员（全员文档只发管理员，部门管理员批不了它）。 */
    public void notifyPendingApproval(String source, String visibility) {
        try {
            List<String> recipients;
            if (Visibility.ALL.equals(visibility)) {
                recipients = jdbcTemplate.query("SELECT username FROM app_user WHERE role = 'ADMIN'",
                        (rs, rowNum) -> rs.getString(1));
            } else {
                recipients = jdbcTemplate.query(
                        "SELECT username FROM app_user WHERE role = 'ADMIN' "
                                + "OR (role = 'DEPT_ADMIN' AND department = ?)",
                        (rs, rowNum) -> rs.getString(1), visibility);
            }
            for (String recipient : recipients) {
                create(recipient, TYPE_PENDING, "有文档待审批",
                        "文档「" + source + "」已上传，待你审批。", source);
            }
        } catch (Exception e) {
            log.warn("待审批通知发送失败（上传本身已成功）：{}", e.toString());
        }
    }

    /**
     * 审批结果通知上传人：通过/驳回。上传人从最近一次入库任务里找（7 天前的任务已被清理，找不到就不发）；
     * 上传人就是审批人本人时不发。
     */
    public void notifyDecision(String source, String actorUsername, boolean approved) {
        try {
            List<String> uploaders = jdbcTemplate.query(
                    "SELECT username FROM ingest_job WHERE source = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> rs.getString(1), source);
            if (uploaders.isEmpty() || uploaders.get(0).equals(actorUsername)) {
                return;
            }
            create(uploaders.get(0), approved ? TYPE_APPROVED : TYPE_REJECTED,
                    approved ? "文档已通过审批" : "文档被驳回",
                    "文档「" + source + "」" + (approved ? "已通过审批，问答可以查到它了。" : "被驳回了，请修改后重新上传。"),
                    source);
        } catch (Exception e) {
            log.warn("审批结果通知发送失败（审批本身已成功）：{}", e.toString());
        }
    }

    /** 自己的通知列表：最新在前，含未读数与总数。 */
    public List<NotificationItem> list(String username, int page, int size) {
        return jdbcTemplate.query(
                "SELECT id, type, title, body, related_source, is_read, created_at FROM notification "
                        + "WHERE username = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new NotificationItem(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("type"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("related_source"),
                        rs.getBoolean("is_read"),
                        rs.getTimestamp("created_at").toInstant()),
                username, size, page * size);
    }

    public int total(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }

    public int unreadCount(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE username = ? AND is_read = FALSE",
                Integer.class, username);
        return count == null ? 0 : count;
    }

    /** 标一条为已读：只改自己的，返回是否改到（没改到就是不存在或不是自己的）。 */
    public boolean markRead(String username, UUID id) {
        return jdbcTemplate.update(
                "UPDATE notification SET is_read = TRUE WHERE id = ? AND username = ?", id, username) > 0;
    }

    /** 全部标已读，返回改动的条数。 */
    public int markAllRead(String username) {
        return jdbcTemplate.update(
                "UPDATE notification SET is_read = TRUE WHERE username = ? AND is_read = FALSE", username);
    }
}
