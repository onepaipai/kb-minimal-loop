package com.example.kb.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * DashScope 原生重排接口（qwen3-rerank，2026-11-18 对阿里云文档核实）：
 * POST {baseUrl}，体为 {model, input:{query, documents}, parameters:{top_n}}，
 * 返回 output.results[]（index + relevance_score，按分排好）。gte-rerank 已停用，不用。
 * 超时、4xx/5xx、解析失败都直接抛异常，由 RerankService 回退 RRF 顺序。
 */
@Component
public class DashScopeRerankClient implements RerankClient {

    private final RerankProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;

    public DashScopeRerankClient(RerankProperties properties, ObjectMapper objectMapper, RestClient restClient,
                                 @Value("${langchain4j.dashscope.api-key}") String apiKey) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public List<RerankedDoc> rerank(String query, List<String> documents, int topN) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", properties.getModel());
        ObjectNode input = body.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        documents.forEach(docs::add);
        body.putObject("parameters").put("top_n", topN);

        JsonNode root = restClient.post()
                .uri(properties.getBaseUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (root == null || !root.has("output")) {
            String message = root == null ? null : root.path("message").asText(null);
            throw new RerankException("重排接口返回异常：" + (message == null ? "无 output" : message));
        }
        List<RerankedDoc> ranked = new ArrayList<>();
        for (JsonNode result : root.path("output").path("results")) {
            ranked.add(new RerankedDoc(result.path("index").asInt(), result.path("relevance_score").asDouble()));
        }
        return ranked;
    }

    /** 重排专用的 RestClient：连接 2 秒、读取 8 秒超时，重排慢了不等，直接回退。 */
    static RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 重排调用失败的异常类型（调用方 catch 后回退，不区分具体原因）。 */
    static class RerankException extends RuntimeException {
        RerankException(String message) {
            super(message);
        }
    }
}
