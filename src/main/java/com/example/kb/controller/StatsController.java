package com.example.kb.controller;

import java.util.List;

import com.example.kb.service.StatsService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 仪表盘趋势统计：最近 N 天的反馈、盲区与入库量，仅管理员。 */
@RestController
@RequestMapping("/api/admin")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 最近 days 天趋势，默认 7 天，限 1-30；返回按日期升序的连续序列。 */
    @GetMapping("/stats/trend")
    @PreAuthorize("hasRole('ADMIN')")
    public List<StatsService.DailyStat> trend(@RequestParam(value = "days", defaultValue = "7") int days) {
        if (days < 1 || days > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days 仅允许 1-30");
        }
        return statsService.trend(days);
    }
}
