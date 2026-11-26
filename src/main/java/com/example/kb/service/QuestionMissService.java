package com.example.kb.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 知识盲区：问答没答上（检索无命中或模型明确答“资料里没找到”）时落一条记录，
 * 盲区看板按问题原文聚合出“问得多又答不上”的知识缺口。记录失败只打 WARN，不影响问答主流程。
 */
@Service
public class QuestionMissService {

    private static final Logger log = LoggerFactory.getLogger(QuestionMissService.class);
    /** 问题原文截断长度：盲区聚合按原文分组，超长的截断后聚合 */
    private static final int MAX_QUESTION_LENGTH = 200;

    private final JdbcTemplate jdbcTemplate;

    public QuestionMissService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 盲区聚合条目：问题原文、提问次数、最近一次提问时间。 */
    public record MissStat(String question, long count, OffsetDateTime lastAskedAt) {
    }

    public void record(String username, String department, String question) {
        try {
            jdbcTemplate.update("INSERT INTO question_miss(id, username, department, question) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), username, department, truncate(question));
        } catch (Exception e) {
            log.warn("知识盲区记录写入失败 user={}", username, e);
        }
    }

    /**
     * 盲区聚合：按问题原文分组，次数多在前；keyword 不为空时只看包含它的（忽略大小写）。
     * department 为 null 看全部，传了只看该部门的（部门管理员视角）。
     */
    public List<MissStat> stats(String department, String keyword, int limit) {
        StringBuilder where = new StringBuilder("1=1");
        List<Object> params = new ArrayList<>();
        if (department != null) {
            where.append(" AND department = ?");
            params.add(department);
        }
        String cleanedKeyword = DocumentManageService.blankToNull(keyword);
        if (cleanedKeyword != null) {
            where.append(" AND question ILIKE ?");
            params.add("%" + cleanedKeyword + "%");
        }
        params.add(limit);
        return jdbcTemplate.query(
                "SELECT question, count(*) AS cnt, max(created_at) AS last_asked FROM question_miss WHERE "
                        + where + " GROUP BY question ORDER BY cnt DESC, last_asked DESC LIMIT ?",
                (rs, rowNum) -> new MissStat(rs.getString("question"), rs.getLong("cnt"),
                        rs.getObject("last_asked", OffsetDateTime.class)),
                params.toArray());
    }

    private static String truncate(String question) {
        if (question == null) {
            return "";
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            return question.substring(0, MAX_QUESTION_LENGTH);
        }
        return question;
    }
}
