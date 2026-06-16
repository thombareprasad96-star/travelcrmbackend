package com.crm.travelcrm.vendor.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class VendorStatsDTO {
    private long total;
    private long active;
    private long inactive;
    private long blacklisted;
    private Map<String, Long> totalByType;
    private BigDecimal totalBusiness;
    private BigDecimal totalPaid;
    private BigDecimal totalOutstanding;
    private double avgRating;
    private long totalBookings;
}