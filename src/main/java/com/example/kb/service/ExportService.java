package com.example.kb.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 管理侧导出：审计日志、知识盲区、文档清单、会话消息四类，输出 CSV / JSON / Markdown。
 * 纯字符串拼接，不引入 POI/EasyExcel；行数上限 5000 防止大库一次拉爆。
 */
@Service
public class ExportService {

    private static final int MAX_ROWS = 5000;

    private final JdbcTemplate jdbcTemplate;

    public ExportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 导出为 UTF-8 字节；dataset 与 format 由 Controller 校验后再传进来。 */
    public byte[] export(String dataset, String format, String username) {
        List<List<String>> table = rows(dataset, username);
        String text;
        if ("json".equals(format)) {
            text = toJson(table);
        } else if ("md".equals(format)) {
            text = toMarkdown(table);
        } else {
            text = toCsv(table);
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 每个 dataset 一个分支：第一行是表头，其余是数据行。 */
    private List<List<String>> rows(String dataset, String username) {
        if ("audit".equals(dataset)) {
            List<List<String>> out = new ArrayList<>();
            out.add(List.of("username", "action", "target", "detail", "createdAt"));
            jdbcTemplate.query(
                    "SELECT username, action, COALESCE(target,''), COALESCE(detail,''), created_at "
                            + "FROM audit_log ORDER BY created_at DESC LIMIT " + MAX_ROWS,
                    (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), String.valueOf(rs.getObject(5, OffsetDateTime.class))))
                    .forEach(out::add);
            return out;
        }
        if ("miss".equals(dataset)) {
            List<List<String>> out = new ArrayList<>();
            out.add(List.of("question", "count", "lastAskedAt"));
            jdbcTemplate.query(
                    "SELECT question, count(*), max(created_at) FROM question_miss "
                            + "GROUP BY question ORDER BY count(*) DESC LIMIT " + MAX_ROWS,
                    (rs, rowNum) -> List.of(rs.getString(1), String.valueOf(rs.getLong(2)),
                            String.valueOf(rs.getObject(3, OffsetDateTime.class))))
                    .forEach(out::add);
            return out;
        }
        if ("docs".equals(dataset)) {
            List<List<String>> out = new ArrayList<>();
            out.add(List.of("source", "chunks", "visibility", "status", "version"));
            jdbcTemplate.query(
                    "SELECT source, chunk_count, visibility, status, version FROM document_version "
                            + "WHERE lifecycle_state = 'CURRENT' ORDER BY source LIMIT " + MAX_ROWS,
                    (rs, rowNum) -> List.of(rs.getString(1), String.valueOf(rs.getInt(2)),
                            rs.getString(3), rs.getString(4), String.valueOf(rs.getInt(5))))
                    .forEach(out::add);
            return out;
        }
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("sessionId", "role", "content", "createdAt"));
        jdbcTemplate.query(
                "SELECT session_id, role, content, created_at FROM chat_message WHERE username = ? "
                        + "ORDER BY created_at DESC LIMIT " + MAX_ROWS,
                (rs, rowNum) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getObject(4, OffsetDateTime.class))), username)
                .forEach(out::add);
        return out;
    }

    private static String toCsv(List<List<String>> table) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : table) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(csv(row.get(i))).append('"');
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static String csv(String value) {
        return value == null ? "" : value.replace("\"", "\"\"");
    }

    private static String toMarkdown(List<List<String>> table) {
        if (table.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> header = table.get(0);
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("| ").append(String.join(" | ", header.stream().map(h -> "---").toList())).append(" |\n");
        for (int i = 1; i < table.size(); i++) {
            sb.append("| ").append(String.join(" | ", table.get(i).stream()
                    .map(v -> v.replace("|", "\\|").replace("\n", " ")).toList())).append(" |\n");
        }
        return sb.toString();
    }

    private static String toJson(List<List<String>> table) {
        if (table.isEmpty()) {
            return "[]";
        }
        List<String> header = table.get(0);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i < table.size(); i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append('{');
            List<String> row = table.get(i);
            for (int j = 0; j < header.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append('"').append(json(header.get(j))).append("\":\"")
                        .append(json(j < row.size() ? row.get(j) : "")).append('"');
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
