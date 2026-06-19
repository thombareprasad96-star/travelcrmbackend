package com.crm.travelcrm.reminder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} (for {@code ReminderScheduler}) and {@code @Async} without
 * touching the main application class, keeping the reminder module plug-and-play.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class ReminderSchedulingConfig {
}