package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetLegChangeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PATCH /api/fleet/trips/{publicId}/swap — hand a running trip over to another vehicle and/or driver.
 *
 * <p>The most common exception in fleet operations, and the one the leg model exists for: a
 * breakdown 200 km into a Nepal run, a relief driver after duty hours, a vehicle reallocated by the
 * office. Before legs there were two options — overwrite the trip's vehicle and corrupt it, or
 * cancel and re-create and lose the odometer chain.
 */
@Getter
@Setter
public class FleetTripSwapDto {

    /** The vehicle taking over. Omit to keep the current one (a pure driver handover). */
    private UUID vehiclePublicId;

    /** The driver taking over. Omit to keep the current one (a pure vehicle substitution). */
    private UUID driverPublicId;

    /**
     * Required. A swap with no recorded reason is unauditable — and this is the field an owner reads
     * when a trip cost twice what it should have.
     */
    @NotNull(message = "Say why the vehicle or driver changed")
    private FleetLegChangeReason changeReason;

    /** Reading on the OUTGOING vehicle when it stopped. */
    @PositiveOrZero
    private Integer atOdometer;

    /** Reading on the INCOMING vehicle when it took over — a different vehicle, a different number. */
    @PositiveOrZero
    private Integer newStartOdometer;

    /** When the handover happened. Defaults to now in the tenant's own timezone. */
    private LocalDateTime at;

    private String notes;
}
