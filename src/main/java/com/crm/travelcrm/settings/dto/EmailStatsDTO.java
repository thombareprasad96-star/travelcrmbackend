package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Email send stats for the settings hub card. Note: "sent" means the SMTP server ACCEPTED the
 * message (submission), not that it was delivered — async bounces (bad address, full mailbox) are
 * not detectable here, so we deliberately do NOT report a "delivery rate". "failed" counts only
 * synchronous send failures (auth/connection/rejected-at-submit).
 */
@Data
@AllArgsConstructor
public class EmailStatsDTO {
    private long sentToday;      // emails accepted by the SMTP server today
    private long failedToday;    // emails that failed at send time today
}