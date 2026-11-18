package com.example.kb.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 重排客户端的 RestClient Bean：带超时的独立实例，不与聊天/向量模型共用。 */
@Configuration
public class RerankConfig {

    @Bean
    public RestClient rerankRestClient() {
        return DashScopeRerankClient.restClient();
    }
}
