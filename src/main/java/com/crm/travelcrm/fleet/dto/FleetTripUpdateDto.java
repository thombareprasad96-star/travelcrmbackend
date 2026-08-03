package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PUT /api/fleet/trips/{publicId} body — partial update, only non-null fields applied.
 * Vehicle/driver may only change while the trip is PLANNED; status is never updated here
 * (use start/close/cancel).
 */
@Getter
@Setter
public class FleetTripUpdateDto {

    private UUID vehiclePublicId;

    private UUID driverPublicId;

    private UUID bookingPublicId;

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

    /**
     * Trip-level foreign currency and its rate — see {@code FleetTripCreateDto}.
     *
     * <p>Editing the rate here does NOT restate costs already recorded: each expense freezes the
     * rate onto its own row at write time, so a correction to this field only affects rows entered
     * afterwards. That is deliberate — a rate that retroactively rewrote reported figures would be
     * the same defect as a status-flip void.
     */
    @Size(max = 3)
    private String fxCurrency;

    @Positive
    private BigDecimal fxRate;
}