package com.example.kb.security;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 简易固定窗口限流（内存实现，重启清零；多实例部署需换 Redis 之类的集中限流）。
 * 已登录用户每分钟 120 次，未登录（按 IP）每分钟 30 次，超了返回 429。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int AUTHENTICATED_LIMIT = 120;
    private static final int ANONYMOUS_LIMIT = 30;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    // ErrorResponse 含 Instant 时间戳，默认 ObjectMapper 序列化不了，需注册 JavaTimeModule
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final class Window {
        long windowStart;
        int count;
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
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        boolean allowed;
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStart >= WINDOW_MILLIS) {
                window.windowStart = now;
                window.count = 0;
            }
            window.count++;
            allowed = window.count <= limit;
        }
        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(), "请求过于频繁，请稍后再试", request.getRequestURI()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
