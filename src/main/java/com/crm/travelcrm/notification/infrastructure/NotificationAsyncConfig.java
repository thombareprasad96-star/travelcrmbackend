package com.crm.travelcrm.notification.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The executor backing {@code @Async("notificationExecutor")} on the email delivery channel.
 *
 * <p><b>This bean did not exist.</b> {@code EmailNotificationChannel.sendAsync} has been annotated
 * {@code @Async("notificationExecutor")} since it was written, but no bean by that name was ever
 * defined anywhere in the source tree. That combination is a startup-clean, runtime-fatal pairing:
 * Spring only resolves the qualifier when the advice actually fires, so the mistake stayed invisible
 * for exactly as long as nothing published an event with the EMAIL channel — which, until the
 * overdue-task alert, nothing ever did.
 *
 * <p>Sizing is deliberately small. This pool exists to keep slow SMTP off the caller's thread, not
 * to send at volume — bulk email is the marketing module's job and has its own batching and rate
 * limiting. A bounded queue plus {@link ThreadPoolExecutor.CallerRunsPolicy} means a genuine flood
 * degrades to the old synchronous behaviour (slow) rather than silently dropping alerts, which for a
 * notification about overdue work is the right trade.
 */
@Configuration
public class NotificationAsyncConfig {

    @Bean("notificationExecutor")
    public Executor notificationExecutor(
            @Value("${app.notification.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.notification.async.max-pool-size:5}") int maxPoolSize,
            @Value("${app.notification.async.queue-capacity:500}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("notify-");
        // Never discard a notification: run it on the caller instead when the queue is full.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight sends finish on shutdown rather than cutting a half-written SMTP conversation.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
