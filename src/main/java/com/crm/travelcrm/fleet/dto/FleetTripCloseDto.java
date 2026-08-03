package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PATCH /api/fleet/trips/{publicId}/close body. Actual end defaults to now when omitted.
 *
 * <p>OLD — removed in the ledger cutover:
 * <pre>
 *   {@literal @}PositiveOrZero private BigDecimal fuelCost;
 *   {@literal @}PositiveOrZero private BigDecimal tollCost;
 *   {@literal @}PositiveOrZero private BigDecimal driverAllowance;
 * </pre>
 *
 * <p>Closing a trip used to collect these three figures, and the expense ledger collects the same
 * money. Both paths were live at once, so every closed trip accumulated a scalar total AND a set of
 * ledger rows for it — one tank of diesel entered in both places is Rs 12,000 of fuel. Dropping them
 * from the close request stops any NEW trip from doing that.
 *
 * <p>An older client that still posts them is harmless: {@code FAIL_ON_UNKNOWN_PROPERTIES} is off, so
 * the fields are simply ignored rather than rejected.
 *
 * <p>The columns remain on the entity, remain editable from the trip edit form, and remain readable —
 * existing trips carry real figures that have not been migrated. Migrating them is a separate step
 * that has to be reconciled against the ledger; see {@code docs/FLEET_MODULE_REDESIGN.md} §5.
 */
@Getter
@Setter
public class FleetTripCloseDto {

    @NotNull
    @PositiveOrZero
    private Integer endOdometer;

    private LocalDateTime endDatetime;

    private String remarks;
}
