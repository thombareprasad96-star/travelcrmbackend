package com.crm.travelcrm.customer.dto.response;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Aggregated dashboard figures for {@code GET /api/customers/stats}.
 * Keys match the object the UI destructures in {@code customerService.getStats()}.
 */
@Value
@Builder
public class CustomerStatsResponse {

    long total;
    long active;
    long inactive;
    long blocked;

    long vip;
    long corporate;
    long regular;

    BigDecimal totalRevenue;
    long totalBookings;

    /** Customers with 3 or more bookings. */
    long repeatCustomers;
}