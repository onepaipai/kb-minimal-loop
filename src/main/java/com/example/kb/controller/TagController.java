package com.example.kb.controller;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 标签/分类聚合：从知识条目与块元数据动态汇总，不新建字典表。 */
@RestController
@RequestMapping("/api/admin")
public class TagController {

    private final JdbcTemplate jdbcTemplate;

    public TagController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 分类与标签字典：各按出现次数倒序，前端做筛选下拉。 */
    @GetMapping("/tags")
    @PreAuthorize("hasRole('ADMIN') or hasRole('DEPT_ADMIN')")
    public Map<String, Object> tags() {
        List<Map<String, Object>> categories = jdbcTemplate.queryForList(
                "SELECT category AS name, count(*) AS cnt FROM knowledge_entry "
                        + "WHERE category IS NOT NULL AND category <> '' AND NOT EXISTS ("
                        + "SELECT 1 FROM document d WHERE d.source = knowledge_entry.source AND d.deleted_at IS NOT NULL) "
                        + "GROUP BY category ORDER BY cnt DESC LIMIT 100");
        List<Map<String, Object>> tags = jdbcTemplate.queryForList(
                "SELECT btrim(tag) AS name, count(*) AS cnt FROM ("
                        + "SELECT unnest(string_to_array(tags, ',')) AS tag FROM knowledge_entry "
                        + "WHERE tags IS NOT NULL AND tags <> '' AND NOT EXISTS ("
                        + "SELECT 1 FROM document d WHERE d.source = knowledge_entry.source AND d.deleted_at IS NOT NULL)"
                        + ") t WHERE tag IS NOT NULL AND btrim(tag) <> '' "
                        + "GROUP BY btrim(tag) ORDER BY cnt DESC LIMIT 100");
        return Map.of("categories", categories, "tags", tags);
    }
}
