package com.example.kb.it;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 鉴权链路集成测试：登录、Refresh 轮换、退出吊销、改密码。
 * 只跑在真实库上：KB_IT_ENABLED=true 且配好 DB_URL/DB_USER/DB_PASSWORD 与 KB_IT_ADMIN_USER/KB_IT_ADMIN_PASSWORD；
 * 平时（包括 CI）自动跳过。改密码只动临时用户 it-pw-user，绝不动管理员密码。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "KB_IT_ENABLED", matches = "true")
class AuthFlowIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void loginRefreshLogoutFlow() {
        String base = ItSupport.baseUrl(port);
        String adminUser = ItSupport.adminUser();
        String adminPassword = ItSupport.adminPassword();

        ResponseEntity<Map> login = rest.postForEntity(base + "/api/auth/login",
                Map.of("username", adminUser, "password", adminPassword), Map.class);
        assertEquals(200, login.getStatusCode().value());
        String refreshToken = (String) login.getBody().get("refreshToken");
        assertNotNull(refreshToken, "登录响应里没有 refreshToken");

        ResponseEntity<Map> refreshed = rest.postForEntity(base + "/api/auth/refresh",
                Map.of("refreshToken", refreshToken), Map.class);
        assertEquals(200, refreshed.getStatusCode().value());
        String newRefreshToken = (String) refreshed.getBody().get("refreshToken");
        assertNotEquals(refreshToken, newRefreshToken, "轮换后 Refresh Token 应该换新");

        ResponseEntity<Map> oldRefresh = rest.postForEntity(base + "/api/auth/refresh",
                Map.of("refreshToken", refreshToken), Map.class);
        assertEquals(401, oldRefresh.getStatusCode().value(), "轮换掉的旧 Refresh Token 应该失效");

        ResponseEntity<Map> logout = rest.postForEntity(base + "/api/auth/logout",
                Map.of("refreshToken", newRefreshToken), Map.class);
        assertEquals(200, logout.getStatusCode().value());

        ResponseEntity<Map> revoked = rest.postForEntity(base + "/api/auth/refresh",
                Map.of("refreshToken", newRefreshToken), Map.class);
        assertEquals(401, revoked.getStatusCode().value(), "退出后 Refresh Token 应该失效");

        ResponseEntity<Map> badLogin = rest.postForEntity(base + "/api/auth/login",
                Map.of("username", adminUser, "password", "wrong-password-123"), Map.class);
        assertEquals(401, badLogin.getStatusCode().value());
    }

    @Test
    void changePasswordFlow() {
        String base = ItSupport.baseUrl(port);
        String adminToken = ItSupport.login(rest, port, ItSupport.adminUser(), ItSupport.adminPassword());
        try {
            jdbcTemplate.update("DELETE FROM app_user WHERE username = 'it-pw-user'");
            ResponseEntity<Map> created = rest.exchange(base + "/api/admin/users", HttpMethod.POST,
                    ItSupport.jsonEntity(adminToken,
                            Map.of("username", "it-pw-user", "password", "it-password-123", "role", "USER")),
                    Map.class);
            assertEquals(200, created.getStatusCode().value());

            String userToken = ItSupport.login(rest, port, "it-pw-user", "it-password-123");
            ResponseEntity<Map> changed = rest.exchange(base + "/api/auth/change-password", HttpMethod.POST,
                    ItSupport.jsonEntity(userToken,
                            Map.of("oldPassword", "it-password-123", "newPassword", "it-password-456")),
                    Map.class);
            assertEquals(200, changed.getStatusCode().value());

            String newToken = ItSupport.login(rest, port, "it-pw-user", "it-password-456");
            assertNotNull(newToken);
            ResponseEntity<Map> oldLogin = rest.postForEntity(base + "/api/auth/login",
                    Map.of("username", "it-pw-user", "password", "it-password-123"), Map.class);
            assertEquals(401, oldLogin.getStatusCode().value(), "改密码后旧密码应该失效");
        } finally {
            jdbcTemplate.update("DELETE FROM app_user WHERE username = 'it-pw-user'");
        }
    }
}
