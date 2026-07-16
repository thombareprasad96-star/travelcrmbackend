package com.crm.travelcrm.calendar.dto;

import java.math.BigDecimal;

/**
 * Aggregate tuple (a monetary sum + a row count) returned by the calendar's outstanding-balance
 * queries via a JPQL constructor expression. {@code amount} is null when no rows match (SUM over an
 * empty set) — callers coalesce to zero.
 */
public record SumCount(BigDecimal amount, Long count) {
}
