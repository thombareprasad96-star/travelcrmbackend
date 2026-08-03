package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetCashDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * POST /api/fleet/cash — one movement on a driver's imprest account.
 *
 * <p><b>Amount is always positive.</b> The direction carries the sign. A signed amount would let a
 * {@code CASH_RETURN} of −1800 ADD 1800 to what the driver owes rather than discharging it, so it is
 * rejected by the annotation here, by the calculator, and by a CHECK constraint.
 *
 * <p>As everywhere in this module, {@code fxRate}, {@code baseAmount} and {@code postingDate} are
 * absent by design — the server owns them.
 */
@Getter
@Setter
public class FleetCashEntryRequestDto {

    @NotNull
    private UUID driverPublicId;

    /** Optional: an opening balance or a general float is not against any one trip. */
    private UUID tripPublicId;

    @NotNull
    private FleetCashDirection direction;

    @NotNull
    @Positive(message = "Amount must be greater than 0 — the direction carries the sign")
    private BigDecimal amount;

    @Size(max = 3)
    private String currency;

    /** Defaults to today in the tenant's own timezone. */
    private LocalDate entryDate;

    /** Mandatory for RECOVERY and both ADJUSTMENT directions. */
    @Size(max = 300)
    private String reason;

    @Size(max = 60)
    private String referenceNumber;

    /** Whose money it is — required for customer collections and deposits. */
    @Size(max = 120)
    private String partyReference;

    private String notes;
}
