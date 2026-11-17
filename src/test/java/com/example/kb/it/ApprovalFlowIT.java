package com.example.kb.it;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批链路集成测试：EDITOR 上传待审批，审批前问不到，管理员批准后才能问到。
 * 只跑在真实库与真实 API Key 上：KB_IT_ENABLED=true 且配好 DB_URL/DB_USER/DB_PASSWORD、
 * DEEPSEEK_API_KEY/DASHSCOPE_API_KEY 与 KB_IT_ADMIN_USER/KB_IT_ADMIN_PASSWORD；平时（包括 CI）自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "KB_IT_ENABLED", matches = "true")
class ApprovalFlowIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM chunk_embedding WHERE metadata->>'source' = 'it-test-doc2.txt'");
        jdbcTemplate.update("DELETE FROM app_user WHERE username = 'it-editor'");
    }

    @Test
    void editorUploadPendingUntilApproved() {
        String base = ItSupport.baseUrl(port);
        String adminToken = ItSupport.login(rest, port, ItSupport.adminUser(), ItSupport.adminPassword());

        ResponseEntity<Map> created = rest.exchange(base + "/api/admin/users", HttpMethod.POST,
                ItSupport.jsonEntity(adminToken,
                        Map.of("username", "it-editor", "password", "it-password-123", "role", "EDITOR")),
                Map.class);
        assertEquals(200, created.getStatusCode().value());
        String editorToken = ItSupport.login(rest, port, "it-editor", "it-password-123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(editorToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource("src/test/resources/it-test-doc2.txt"));
        body.add("visibility", "all");
        ResponseEntity<Map> ingest = rest.exchange(base + "/api/ingest", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertEquals(200, ingest.getStatusCode().value());
        assertEquals("pending", ingest.getBody().get("status"), "EDITOR 上传的文档应该待审批");

        assertTrue(askHits(base, editorToken).isEmpty(), "审批前问答不应该命中待审批文档");

        ResponseEntity<Map> approved = rest.exchange(
                base + "/api/document/approve?source=it-test-doc2.txt", HttpMethod.POST,
                new HttpEntity<>(null, ItSupport.authHeaders(adminToken)), Map.class);
        assertEquals(200, approved.getStatusCode().value());

        assertFalse(askHits(base, editorToken).isEmpty(), "批准后问答应该命中该文档");
    }

    private List<?> askHits(String base, String token) {
        ResponseEntity<Map> ask = rest.exchange(base + "/api/ask", HttpMethod.POST,
                ItSupport.jsonEntity(token, Map.of("question", "it-test 文档里加班怎么算？",
                        "sessionId", "it-approve-1")),
                Map.class);
        assertEquals(200, ask.getStatusCode().value());
        List<?> hits = (List<?>) ask.getBody().get("hits");
        return hits == null ? List.of() : hits;
    }
}
