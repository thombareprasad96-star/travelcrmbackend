package com.crm.travelcrm.quotation.analytics;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Weblink-view analytics for one quotation: summary cards + per-IP rows. */
@Data
@Builder
public class WeblinkAnalyticsDto {

    private Summary summary;
    private List<Row> rows;

    @Data
    @Builder
    public static class Summary {
        private long totalViews;
        private long externalViews;
        private long homeIpViews;
        private long uniqueIps;
    }

    @Data
    @Builder
    public static class Row {
        private String ipAddress;
        private ViewerType type;
        private int views;
        private Instant firstViewedAt;
        private Instant lastViewedAt;
    }
}