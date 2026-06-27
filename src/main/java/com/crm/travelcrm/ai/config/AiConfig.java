package com.crm.travelcrm.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Disha runtime beans. The chat happens on a dedicated, bounded worker pool (off the servlet thread)
 * so SSE responses can stream; the orchestration re-establishes SecurityContext + TenantContext on
 * that worker thread (both are plain ThreadLocals and do NOT inherit onto pool threads).
 *
 * <p>The {@code ChatClient}/{@code OllamaChatModel} beans are contributed by Spring AI's Ollama
 * auto-configuration; they are constructed lazily and never ping Ollama at startup, so the app boots
 * even when Ollama is not running.
 */
@Configuration
public class AiConfig {

    @Bean(name = "dishaTaskExecutor")
    public Executor dishaTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("disha-chat-");
        executor.initialize();
        return executor;
    }
}