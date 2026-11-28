package com.example.kb.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 应用内的有界线程池：异步入库与 SSE 流式问答各用一套，互不拖垮。 */
@Configuration
public class AsyncConfig {

    @Bean("ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        // 守护线程：启动失败（如连不上库）时 JVM 能直接退出，不会被池子里的空闲线程吊住
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    /** SSE 流式问答专用线程池：与入库隔离，避免长连接占满 commonPool；客户端断开时任务被取消。 */
    @Bean("streamExecutor")
    public Executor streamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("stream-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    /** 混合检索专用有界线程池：慢数据库查询不能无限堆积到 commonPool。 */
    @Bean("hybridSearchExecutor")
    public Executor hybridSearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("hybrid-search-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
