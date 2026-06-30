package com.crm.travelcrm.lead.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** One card in the All-Lead-Logs grid: a lead plus its latest log and total log count. */
@Data
@Builder
public class LeadLogCardDto {
    private UUID leadId;        // lead publicId — never the internal Long id
    private String leadName;
    private String phone;
    private String stage;       // lead's current stage (display name)
    private long logCount;
    private LatestLog latestLog;

    @Data
    @Builder
    public static class LatestLog {
        private String date;          // "MMM dd, yyyy HH:mm"
        private String comment;
        private String addedBy;
        private String followUpDate;  // "MMM dd, yyyy" or null
    }
}