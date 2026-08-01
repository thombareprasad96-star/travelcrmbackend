package com.crm.travelcrm.report.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardAnalyticsResponse {
    private long totalLeads;
    private long convertedLeads;
    private double conversionRate;
    /** Σ customerAmount of ACTIVE bookings (cancelled/refunded excluded). Package sales only. */
    private BigDecimal revenue;

    /**
     * Cancellation charge retained in the period, NET of GST/TCS (the pre-tax charge base).
     * Revenue with no service delivered behind it, so it is reported on its own line rather than
     * merged into {@link #revenue} — merging would corrupt margin %, realisation per pax and every
     * conversion metric derived from package sales.
     */
    private BigDecimal retainedCancellationCharges;

    /** {@link #revenue} + {@link #retainedCancellationCharges} — total the agency earned. */
    private BigDecimal agencyRevenue;

    /** Profit on delivered travel — cancelled/refunded bookings excluded. A sales metric. */
    private BigDecimal profit;

    /**
     * Profit on cancelled bookings — retained charge less the vendor and internal costs that could
     * not be recovered. Frequently NEGATIVE, and that is the point: it says whether the cancellation
     * slab is priced above the agency's real sunk cost. A churn metric, not a sales one.
     */
    private BigDecimal cancelledProfit;

    /** {@link #profit} + {@link #cancelledProfit} — total operating gross profit. */
    private BigDecimal totalProfit;

    private double netMargin;
    private BigDecimal refunds;
    private double winRate;
    private long hotLeads;

    private List<LeadSourceSlice> leadSources;
    private List<TopDestination> topDestinations;
    private List<RevenueTimelinePoint> revenueTimeline;
    private List<PerformerConversion> topPerformersConv;
    private List<PerformerProfit> topPerformersProfit;
    private List<PriorityFollowup> priorityFollowups;
    private List<NearTravelDate> nearTravelDates;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeadSourceSlice {
        private String name;
        private long value;
        private String color;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopDestination {
        private String name;
        private long bookings;
        private BigDecimal revenue;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenueTimelinePoint {
        private String month;
        private BigDecimal revenue;
        private long bookings;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformerConversion {
        private int rank;
        private String name;
        private long leads;
        private long conversions;
        private BigDecimal revenue;
        private BigDecimal profit;
        private double rate;
        private String tier;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformerProfit {
        private int rank;
        private String name;
        private long leads;
        private long conversions;
        private BigDecimal revenue;
        private BigDecimal profit;
        private double margin;
        private long converted;
        private String tier;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriorityFollowup {
        private String name;
        private String phone;
        private String note;
        private String dueDate;
        private String urgency;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NearTravelDate {
        private String name;
        private String phone;
        private String travelDate;
        private String stage;
        private long daysLeft;
    }
}
