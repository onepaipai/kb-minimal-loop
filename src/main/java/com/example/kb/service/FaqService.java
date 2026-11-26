package com.example.kb.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 标准答案（置顶 FAQ）：管理员维护的权威答案，问答时优先于检索直接返回。
 * 匹配规则：问题原文一致（忽略大小写），或问题里包含任一关键词（逗号分隔，忽略大小写）。
 */
@Service
public class FaqService {

    private final JdbcTemplate jdbcTemplate;

    public FaqService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 标准答案条目：问题、答案、匹配关键词、生效部门（all 或代号）、启用标志。 */
    public record FaqEntry(UUID id, String question, String answer, String keywords,
                           String department, boolean enabled, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public FaqEntry create(String question, String answer, String keywords, String department, String username) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO standard_answer(id, question, answer, keywords, department, username) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, question.trim(), answer.trim(),
                normalizeKeywords(keywords), department, username);
        return get(id);
    }

    /** 修改：answer/keywords/department/enabled 为 null 的字段不更新；不存在报 404。 */
    public FaqEntry update(UUID id, String answer, String keywords, String department, Boolean enabled) {
        get(id);
        if (answer != null && !answer.isBlank()) {
            jdbcTemplate.update("UPDATE standard_answer SET answer = ?, updated_at = now() WHERE id = ?",
                    answer.trim(), id);
        }
        if (keywords != null) {
            jdbcTemplate.update("UPDATE standard_answer SET keywords = ?, updated_at = now() WHERE id = ?",
                    normalizeKeywords(keywords), id);
        }
        String cleanedDepartment = DocumentManageService.blankToNull(department);
        if (cleanedDepartment != null) {
            jdbcTemplate.update("UPDATE standard_answer SET department = ?, updated_at = now() WHERE id = ?",
                    cleanedDepartment.toLowerCase(Locale.ROOT), id);
        }
        if (enabled != null) {
            jdbcTemplate.update("UPDATE standard_answer SET enabled = ?, updated_at = now() WHERE id = ?",
                    enabled, id);
        }
        return get(id);
    }

    /** 删除：返回是否删到（不存在返回 false）。 */
    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM standard_answer WHERE id = ?", id) > 0;
    }

    /** 管理清单：department 为 null 看全部，传了只看该部门的；最近更新在前。 */
    public List<FaqEntry> list(String department) {
        StringBuilder sql = new StringBuilder("SELECT id, question, answer, keywords, department, enabled, "
                + "created_at, updated_at FROM standard_answer");
        List<Object> params = new ArrayList<>();
        if (department != null) {
            sql.append(" WHERE department = ?");
            params.add(department);
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    /**
     * 问答匹配：按更新时间从新到旧找第一条命中的（原文一致或包含任一关键词）。
     * department 为问答的生效部门：null 只匹配全员的，传了匹配全员加该部门的。
     */
    public Optional<FaqEntry> match(String question, String department) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String where;
        List<Object> params = new ArrayList<>();
        if (department == null) {
            where = "enabled = TRUE AND department = 'all'";
        } else {
            where = "enabled = TRUE AND department IN ('all', ?)";
            params.add(department);
        }
        List<FaqEntry> candidates = jdbcTemplate.query(
                "SELECT id, question, answer, keywords, department, enabled, created_at, updated_at "
                        + "FROM standard_answer WHERE " + where + " ORDER BY updated_at DESC",
                (rs, rowNum) -> mapRow(rs), params.toArray());
        String normalizedQuestion = question.trim().toLowerCase(Locale.ROOT);
        for (FaqEntry candidate : candidates) {
            if (matches(candidate, normalizedQuestion)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 命中判断：原文一致（忽略大小写）或问题里包含任一关键词（忽略大小写）。 */
    private static boolean matches(FaqEntry candidate, String normalizedQuestion) {
        String candidateQuestion = candidate.question().trim().toLowerCase(Locale.ROOT);
        if (candidateQuestion.equals(normalizedQuestion)) {
            return true;
        }
        String keywords = candidate.keywords();
        if (keywords == null || keywords.isBlank()) {
            return false;
        }
        for (String keyword : keywords.split("[,，;；]")) {
            String trimmed = keyword.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty() && normalizedQuestion.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** 关键词归一：按中英文逗号/分号拆开、去空格、忽略空段，重新用英文逗号连接；全空返回 null。 */
    private static String normalizeKeywords(String keywords) {
        String cleaned = DocumentManageService.blankToNull(keywords);
        if (cleaned == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String keyword : cleaned.split("[,，;；]")) {
            String trimmed = keyword.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(trimmed);
        }
        if (builder.length() == 0) {
            return null;
        }
        return builder.toString();
    }

    /** 按主键取单条：不存在报 404。 */
    public FaqEntry get(UUID id) {
        List<FaqEntry> entries = jdbcTemplate.query(
                "SELECT id, question, answer, keywords, department, enabled, created_at, updated_at "
                        + "FROM standard_answer WHERE id = ?",
                (rs, rowNum) -> mapRow(rs), id);
        if (entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标准答案不存在");
        }
        return entries.get(0);
    }

    private static FaqEntry mapRow(ResultSet rs) throws SQLException {
        return new FaqEntry(UUID.fromString(rs.getString("id")), rs.getString("question"), rs.getString("answer"),
                rs.getString("keywords"), rs.getString("department"), rs.getBoolean("enabled"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
