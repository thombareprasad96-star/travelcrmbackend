package com.crm.travelcrm.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only aggregate of a single customer's booking activity. Produced by a
 * grouped query in {@code BookingRepository} so the customer module can enrich its
 * responses with lifetime metrics without an N+1 fan-out.
 *
 * <p>Spring Data binds these getters from the {@code SELECT} aliases.</p>
 */
public interface CustomerBookingMetrics {

    Long getCustomerId();

    long getBookingCount();

    BigDecimal getTotalSpent();

    LocalDate getLastBookingDate();
}