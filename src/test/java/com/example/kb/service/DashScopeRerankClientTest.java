package com.example.kb.service;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/** DashScope 重排客户端：请求体与响应解析按阿里云文档的原生接口格式核对，不调真实接口。 */
class DashScopeRerankClientTest {

    private DashScopeRerankClient client(RestClient restClient) {
        RerankProperties properties = new RerankProperties();
        properties.setBaseUrl("http://localhost/rerank");
        properties.setModel("qwen3-rerank");
        return new DashScopeRerankClient(properties, new ObjectMapper(), restClient, "test-key");
    }

    @Test
    void rerankSendsNativeFormatAndParsesResults() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("http://localhost/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("qwen3-rerank"))
                .andExpect(jsonPath("$.input.query").value("什么是重排模型？"))
                .andExpect(jsonPath("$.input.documents[0]").value("doc A"))
                .andExpect(jsonPath("$.input.documents[1]").value("doc B"))
                .andExpect(jsonPath("$.parameters.top_n").value(2))
                .andRespond(withSuccess(
                        "{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},"
                                + "{\"index\":0,\"relevance_score\":0.3}]},\"request_id\":\"x\"}",
                        MediaType.APPLICATION_JSON));
        List<RerankClient.RerankedDoc> result = client(restClient)
                .rerank("什么是重排模型？", List.of("doc A", "doc B"), 2);
        assertEquals(List.of(
                new RerankClient.RerankedDoc(1, 0.9),
                new RerankClient.RerankedDoc(0, 0.3)), result);
        server.verify();
    }

    @Test
    void rerankThrowsOnAuthFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        server.expect(requestTo("http://localhost/rerank"))
                .andRespond(withUnauthorizedRequest());
        assertThrows(Exception.class, () -> client(restClient).rerank("q", List.of("d"), 1));
        server.verify();
    }
}
