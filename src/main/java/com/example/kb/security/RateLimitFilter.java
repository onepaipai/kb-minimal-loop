package com.example.kb.security;

import java.io.IOException;

import com.example.kb.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 固定窗口限流（数据库实现，多实例共用 rate_limit 表）。
 * 已登录用户每分钟 120 次，未登录（按 IP）每分钟 30 次，超了返回 429。
 * 计数用一条 upsert 原子完成，窗口按分钟对齐；过期窗口由定时任务清理。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int AUTHENTICATED_LIMIT = 120;
    private static final int ANONYMOUS_LIMIT = 30;

    private final JdbcTemplate jdbcTemplate;
    // ErrorResponse 含 Instant 时间戳，默认 ObjectMapper 序列化不了，需注册 JavaTimeModule
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public RateLimitFilter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String key;
        int limit;
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            key = "user:" + principal.username();
            limit = AUTHENTICATED_LIMIT;
        } else {
            key = "ip:" + request.getRemoteAddr();
            limit = ANONYMOUS_LIMIT;
        }
        Integer count = null;
        try {
            count = jdbcTemplate.queryForObject(
                    "INSERT INTO rate_limit(rate_key, window_start, request_count) "
                            + "VALUES (?, date_trunc('minute', now()), 1) "
                            + "ON CONFLICT (rate_key) DO UPDATE SET request_count = CASE "
                            + "WHEN rate_limit.window_start = date_trunc('minute', now()) THEN rate_limit.request_count + 1 "
                            + "ELSE 1 END, window_start = date_trunc('minute', now()) RETURNING request_count",
                    Integer.class, key);
        } catch (Exception e) {
            // 限流表写不进去（库断了）不能把整站拖死：放行本次请求，只记一条日志
            org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class)
                    .warn("限流计数写入失败，已放行", e);
        }
        if (count != null && count > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "请求过于频繁，请稍后再试", request.getRequestURI()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
