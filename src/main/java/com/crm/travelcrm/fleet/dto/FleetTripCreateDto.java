package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * POST /api/fleet/trips body. Status is derived, never sent: an {@code endDatetime}
 * makes it a post-facto COMPLETED diary entry; otherwise the trip is created PLANNED
 * and started via PATCH /{publicId}/start.
 */
@Getter
@Setter
public class FleetTripCreateDto {

    @NotNull
    private UUID vehiclePublicId;

    @NotNull
    private UUID driverPublicId;

    /** Optional link to a booking — resolved + tenant-validated server-side. */
    private UUID bookingPublicId;

    @NotNull
    private LocalDateTime startDatetime;

    private LocalDateTime endDatetime;

    @PositiveOrZero
    private Integer startOdometer;

    @PositiveOrZero
    private Integer endOdometer;

    @Size(max = 150)
    private String routeFrom;

    @Size(max = 150)
    private String routeTo;

    @Size(max = 150)
    private String purpose;

    @PositiveOrZero
    private BigDecimal fuelCost;

    @PositiveOrZero
    private BigDecimal tollCost;

    @PositiveOrZero
    private BigDecimal driverAllowance;

    private String remarks;

    // ── Cross-border money ──────────────────────────────────────────────────
    // The office sets ONE rate for the whole trip, here, once. Every foreign-currency cost row on
    // this trip then inherits that rate AT WRITE TIME. This is why no expense request ever carries
    // a rate: a driver at the Sunauli border types a Bhansar receipt in NPR and never sees, knows
    // or influences the conversion. Without these two fields the whole NPR path is unreachable —
    // FleetMoneyCalculator.resolveRate rejects a foreign currency with no trip rate.

    /** Foreign currency in use on this trip, e.g. NPR. Null for a purely domestic trip. */
    @Size(max = 3)
    private String fxCurrency;

    /** Units of base currency per one {@link #fxCurrency}, e.g. 0.625 for NPR→INR. */
    @Positive
    private BigDecimal fxRate;
}