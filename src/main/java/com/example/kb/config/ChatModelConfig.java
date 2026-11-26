package com.example.kb.config;

import java.time.Duration;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DeepSeek 聊天模型配置：普通与流式共用同一套 Key、地址、模型名。 */
@Configuration
public class ChatModelConfig {

    @Value("${langchain4j.deepseek.api-key}")
    private String apiKey;

    @Value("${langchain4j.deepseek.base-url}")
    private String baseUrl;

    @Value("${langchain4j.deepseek.model-name}")
    private String modelName;

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** 流式聊天模型：只换流式类，供 /api/ask-stream 逐 token 推送。 */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
