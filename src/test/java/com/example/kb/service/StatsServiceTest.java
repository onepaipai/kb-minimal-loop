package com.example.kb.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 趋势统计：无数据时仍返回连续 N 天且各项为 0；用 mock 的 JdbcTemplate，不碰真实库。 */
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void trendReturnsContinuousDaysWithZeros() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        StatsService service = new StatsService(jdbcTemplate);

        List<StatsService.DailyStat> rows = service.trend(7);

        assertEquals(7, rows.size());
        assertEquals(0, rows.get(0).feedback());
        assertEquals(0, rows.get(0).misses());
        assertEquals(LocalDate.now().minusDays(6), rows.get(0).date());
        assertEquals(LocalDate.now(), rows.get(6).date());
    }
}
