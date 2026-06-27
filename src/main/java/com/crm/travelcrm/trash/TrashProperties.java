package com.crm.travelcrm.trash;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from {@code app.trash.*}. Keeps the retention window a single configurable
 * value instead of a magic number scattered across the codebase.
 */
@Component
@ConfigurationProperties(prefix = "app.trash")
public class TrashProperties {

    /** Days a soft-deleted record stays recoverable before the scheduled hard-purge. */
    private int retentionDays = 30;

    /** 6-field Spring cron for the daily purge job. */
    private String purgeCron = "0 30 2 * * *";

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getPurgeCron() {
        return purgeCron;
    }

    public void setPurgeCron(String purgeCron) {
        this.purgeCron = purgeCron;
    }
}