package com.example.kb.it;

import java.util.Map;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 集成测试公共小帮手：登录拿 Token、拼 Bearer 头。
 * 不以 Test 结尾，不会被 Surefire 当测试跑。
 */
final class ItSupport {

    private ItSupport() {
    }

    static String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    static String adminUser() {
        String user = System.getenv("KB_IT_ADMIN_USER");
        return user == null || user.isBlank() ? "admin" : user;
    }

    static String adminPassword() {
        return System.getenv("KB_IT_ADMIN_PASSWORD");
    }

    /** 登录并返回响应体里的 token；登录失败直接断言失败。 */
    static String login(TestRestTemplate rest, int port, String username, String password) {
        ResponseEntity<Map> response = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertEquals(200, response.getStatusCode().value(), "登录失败：" + response.getBody());
        String token = (String) response.getBody().get("token");
        assertNotNull(token, "登录响应里没有 token");
        return token;
    }

    static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    static HttpEntity<Map<String, Object>> jsonEntity(String token, Map<String, Object> body) {
        return new HttpEntity<>(body, authHeaders(token));
    }
}
