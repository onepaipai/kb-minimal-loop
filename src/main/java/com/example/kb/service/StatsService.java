package com.example.kb.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 仪表盘趋势：按天聚合反馈、知识盲区与入库任务，缺失的日期补 0，前端直接画。 */
@Service
public class StatsService {

    /** 某天的三项统计：问答反馈数、好评数、盲区数、入库任务数。 */
    public record DailyStat(LocalDate date, long feedback, long helpful, long misses, long ingests) {
    }

    private final JdbcTemplate jdbcTemplate;

    public StatsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 最近 days 天的趋势（含今天），days 限 1-30；SQL 里按 date_trunc 分组，Java 侧补齐空日期。 */
    public List<DailyStat> trend(int days) {
        Map<LocalDate, long[]> counts = new HashMap<>();
        aggregate("SELECT date_trunc('day', created_at)::date AS d, count(*) AS total, "
                + "count(*) FILTER (WHERE helpful) AS helpful FROM feedback "
                + "WHERE created_at >= current_date - (?::int - 1) GROUP BY 1", days, counts, 0, 1);
        aggregate("SELECT date_trunc('day', created_at)::date AS d, count(*) AS total FROM question_miss "
                + "WHERE created_at >= current_date - (?::int - 1) GROUP BY 1", days, counts, 2, -1);
        aggregate("SELECT date_trunc('day', created_at)::date AS d, count(*) AS total FROM ingest_job "
                + "WHERE created_at >= current_date - (?::int - 1) GROUP BY 1", days, counts, 3, -1);

        List<DailyStat> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long[] values = counts.getOrDefault(date, new long[4]);
            result.add(new DailyStat(date, values[0], values[1], values[2], values[3]));
        }
        return result;
    }

    /** 把一列聚合结果写进 counts；helpfulIndex 传 -1 表示该 SQL 没有好评列。 */
    private void aggregate(String sql, int days, Map<LocalDate, long[]> counts, int totalIndex, int helpfulIndex) {
        jdbcTemplate.query(sql, (rs, rowNum) -> {
            LocalDate date = rs.getObject("d", LocalDate.class);
            long[] values = counts.computeIfAbsent(date, key -> new long[4]);
            values[totalIndex] += rs.getLong("total");
            if (helpfulIndex >= 0) {
                values[helpfulIndex] += rs.getLong("helpful");
            }
            return null;
        }, days);
    }
}
