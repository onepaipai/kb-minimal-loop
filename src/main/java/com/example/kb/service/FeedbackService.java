package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 问答反馈：用户对回答点好评/差评，管理员看好评率；问答原文截断存，避免表过大。 */
@Service
public class FeedbackService {

    private static final int MAX_TEXT_LENGTH = 500;

    private final JdbcTemplate jdbcTemplate;

    public FeedbackService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record FeedbackStats(long total, long helpful, double helpfulRate) {
    }

    public void submit(String username, String question, String answer, boolean helpful) {
        String storedQuestion = truncate(question);
        String storedAnswer = truncate(answer);
        String key = feedbackKey(username, storedQuestion, storedAnswer);
        jdbcTemplate.update("INSERT INTO feedback(id, username, question, answer, helpful, feedback_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (feedback_key) DO UPDATE SET helpful = EXCLUDED.helpful, "
                        + "created_at = now()", UUID.randomUUID(), username, storedQuestion, storedAnswer, helpful, key);
    }

    public FeedbackStats stats() {
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM feedback", Long.class);
        Long helpful = jdbcTemplate.queryForObject("SELECT count(*) FROM feedback WHERE helpful = TRUE", Long.class);
        long totalCount = total == null ? 0 : total;
        long helpfulCount = helpful == null ? 0 : helpful;
        double rate = totalCount == 0 ? 0 : (double) helpfulCount / totalCount;
        return new FeedbackStats(totalCount, helpfulCount, rate);
    }

    public List<String> recentQuestions(int limit) {
        return jdbcTemplate.query("SELECT question FROM feedback WHERE helpful = FALSE ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> rs.getString("question"), limit);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }

    private static String feedbackKey(String username, String question, String answer) {
        try {
            String value = username + "\u0000" + question + "\u0000" + answer;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
