package com.example.kb.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 神经重排配置：app.rerank.*；模型默认 qwen3-rerank（gte-rerank 已停用），Key 复用 DASHSCOPE_API_KEY。 */
@Component
@ConfigurationProperties("app.rerank")
public class RerankProperties {

    /** 默认开启；失败时回退 RRF 顺序，关掉则完全不调重排接口 */
    private boolean enabled = true;
    private String model = "qwen3-rerank";
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
