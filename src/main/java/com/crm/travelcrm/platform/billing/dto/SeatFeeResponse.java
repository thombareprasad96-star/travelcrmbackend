package com.crm.travelcrm.platform.billing.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** A tenant's current sub-agent seat-fee position: the effective rate, active seats, and the total. */
@Data
@Builder
public class SeatFeeResponse {

    /** The per-seat monthly rate in effect (per-tenant override, or the platform default). */
    private BigDecimal effectiveRate;

    /** True when {@code effectiveRate} came from the platform-flat default (no per-tenant override). */
    private boolean usingPlatformDefault;

    /** The per-tenant override value, or {@code null} when none is set. */
    private BigDecimal overrideRate;

    /** Active (billable) sub-agents right now. */
    private long activeSeats;

    /** Monthly seat charge = {@code effectiveRate × activeSeats}. */
    private BigDecimal monthlyTotal;
}
