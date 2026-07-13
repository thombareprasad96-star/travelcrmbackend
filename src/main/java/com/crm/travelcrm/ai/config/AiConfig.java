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
 * <p>The {@code ChatClient}/{@code OpenAiChatModel} beans are contributed by Spring AI's OpenAI
 * auto-configuration (pointed at Groq's OpenAI-compatible endpoint). They are constructed lazily and
 * never call Groq at startup, so the app boots even when no API key is set.
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