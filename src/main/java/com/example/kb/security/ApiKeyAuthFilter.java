package com.example.kb.security;

import java.io.IOException;
import java.util.List;

import com.example.kb.security.UserPrincipal;
import com.example.kb.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API Key 鉴权过滤器：请求头 X-API-Key 有效则把 Key 主人放进 SecurityContext。
 * 只对数据接口生效，/api/admin/** 与 /api/auth/** 不认 Key（管理操作与登录只能用 JWT）。
 * scope 为 ask 的 Key 只能调问答与清单接口（ask、ask-stream、bot/ask、documents、versions），调别的直接按没登录处理。
 * 放在 JwtAuthenticationFilter 之前：两个都带时 JWT 优先（后执行的覆盖）。
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String key = request.getHeader("X-API-Key");
        if (key != null && !key.isBlank()
                && !path.startsWith("/api/admin") && !path.startsWith("/api/auth")) {
            apiKeyService.validate(key).ifPresent(validated -> {
                if (ApiKeyService.SCOPE_ASK.equals(validated.scope())
                        && !isAskScopePath(request.getMethod(), path)) {
                    return;
                }
                UserPrincipal principal = validated.principal();
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }

    /** ask 范围的 Key 能调的接口：问答、流式问答、机器人问答、纯检索、文档清单、版本列表（都是只读）。 */
    private static boolean isAskScopePath(String method, String path) {
        return ("POST".equalsIgnoreCase(method) && ("/api/ask".equals(path) || "/api/ask-stream".equals(path)
                || "/api/bot/ask".equals(path) || "/api/search".equals(path)
                || "/api/search/grouped".equals(path)))
                || ("GET".equalsIgnoreCase(method)
                        && ("/api/documents".equals(path) || "/api/document/versions".equals(path)));
    }
}
