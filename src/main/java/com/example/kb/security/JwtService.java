package com.example.kb.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验：HS256，Token 有效期 8 小时。
 * 密钥走环境变量 JWT_SECRET（至少 32 个字符）；不配则启动时随机生成，重启后旧 Token 失效。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Token 有效期 8 小时 */
    private static final long TOKEN_VALIDITY_MILLIS = 8 * 60 * 60 * 1000L;

    private final javax.crypto.SecretKey signingKey;

    public JwtService(@Value("${JWT_SECRET:}") String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            this.signingKey = Jwts.SIG.HS256.key().build();
            log.warn("未配置 JWT_SECRET，已随机生成签名密钥，重启后已签发的 Token 全部失效；生产环境请配置至少 32 个字符的 JWT_SECRET");
        } else if (configuredSecret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET 至少 32 个字符");
        } else {
            this.signingKey = Keys.hmacShaKeyFor(configuredSecret.getBytes(StandardCharsets.UTF_8));
        }
    }

    public String createToken(UserPrincipal user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.username())
                .claim("role", user.role())
                .claim("department", user.department())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + TOKEN_VALIDITY_MILLIS))
                .signWith(signingKey)
                .compact();
    }

    /** 校验失败（过期、签名不对、格式不对）返回 null，调用方按未登录处理。 */
    public UserPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new UserPrincipal(claims.getSubject(), claims.get("role", String.class),
                    claims.get("department", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long validitySeconds() {
        return TOKEN_VALIDITY_MILLIS / 1000;
    }
}
