package com.example.kb.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 异步入库线程池：2 个常驻、最多 4 个并发、排队 100 个任务，任务名 ingest- 前缀便于定位。 */
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
}
