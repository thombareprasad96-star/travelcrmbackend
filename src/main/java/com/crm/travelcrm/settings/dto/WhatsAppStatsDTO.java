package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Integration stats for the WhatsApp sidebar card. */
@Data
@AllArgsConstructor
public class WhatsAppStatsDTO {
    private long   messagesSent;    // this month
    private String deliveryRate;    // "98%" or "—"
    private String lastTestedAt;    // "Jun 25, 2026" or null
    private String apiStatus;       // Active | Inactive
    private String apiStatusSub;    // Connected | Not configured
}