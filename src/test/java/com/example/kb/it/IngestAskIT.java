package com.example.kb.it;

import java.util.List;
import java.util.Map;

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
 * 入库→问答→删除集成测试：上传 it-test-doc.txt，问答命中它，删掉。
 * 只跑在真实库与真实 API Key 上：KB_IT_ENABLED=true 且配好 DB_URL/DB_USER/DB_PASSWORD、
 * DEEPSEEK_API_KEY/DASHSCOPE_API_KEY 与 KB_IT_ADMIN_USER/KB_IT_ADMIN_PASSWORD；平时（包括 CI）自动跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "KB_IT_ENABLED", matches = "true")
class IngestAskIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void ingestAskDeleteRoundTrip() {
        String base = ItSupport.baseUrl(port);
        String token = ItSupport.login(rest, port, ItSupport.adminUser(), ItSupport.adminPassword());
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource("src/test/resources/it-test-doc.txt"));
            body.add("visibility", "all");
            ResponseEntity<Map> ingest = rest.exchange(base + "/api/ingest", HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);
            assertEquals(200, ingest.getStatusCode().value(), "入库失败：" + ingest.getBody());
            assertTrue(((Number) ingest.getBody().get("chunks")).intValue() > 0, "入库块数应大于 0");

            ResponseEntity<Map> ask = rest.exchange(base + "/api/ask", HttpMethod.POST,
                    ItSupport.jsonEntity(token, Map.of("question", "it-test 文档里请假超过 3 天找谁批？",
                            "sessionId", "it-ask-1")),
                    Map.class);
            assertEquals(200, ask.getStatusCode().value(), "问答失败：" + ask.getBody());
            List<?> hits = (List<?>) ask.getBody().get("hits");
            assertFalse(hits == null || hits.isEmpty(), "问答应该命中刚入库的文档");
            assertTrue(((String) ask.getBody().get("answer")).contains("部门经理"),
                    "答案应该提到部门经理：" + ask.getBody().get("answer"));
        } finally {
            jdbcTemplate.update("DELETE FROM chunk_embedding WHERE metadata->>'source' = 'it-test-doc.txt'");
        }
    }
}
