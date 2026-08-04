package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetExpenseType;
import com.crm.travelcrm.fleet.enums.FleetPaidBy;
import com.crm.travelcrm.fleet.enums.FleetTaxCharacter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * POST /api/fleet/expenses and PUT /api/fleet/expenses/{publicId} body.
 *
 * <p><b>Deliberately absent: {@code fxRate}, {@code baseAmount}, {@code postingDate},
 * {@code legPublicId}.</b> Every one is server-owned. The rate comes from the trip, the base amount
 * is computed, the posting date is derived, and the leg is resolved from the document instant. The
 * superseded plan's own sample request posted {@code "fxRateToBase": 0.62500000} from a device on
 * the same page that promised the server does not trust client money — a driver's phone can set any
 * rate it likes, and trusting the rate is the same defect as trusting the total with one extra
 * multiplication.
 */
@Getter
@Setter
public class FleetExpenseRequestDto {

    @NotNull(message = "Vehicle is required — every fleet cost belongs to a vehicle")
    private UUID vehiclePublicId;

    /** Optional: insurance, road tax and an off-duty challan belong to a vehicle, not a trip. */
    private UUID tripPublicId;

    /** Required when {@code paidBy = DRIVER_CASH} — validated in the service, not by annotation. */
    private UUID driverPublicId;

    @NotNull
    private FleetExpenseType expenseType;

    /** The date ON THE RECEIPT — not today, not when it was typed. */
    @NotNull(message = "Receipt date is required")
    private LocalDate documentDate;

    /**
     * Time on the receipt. Required for TOLL, PARKING and CHALLAN on a trip that changed driver, so
     * the row resolves to exactly one leg — a date alone is ambiguous across a handover and silently
     * charges the wrong driver.
     */
    private LocalTime documentTime;

    @NotNull
    @Positive(message = "Amount must be greater than 0")
    private BigDecimal amount;

    /** ISO code. Defaults to INR. NPR is entered as NPR — the driver never sees a rate. */
    @Size(max = 3)
    private String currency;

    @NotNull
    private FleetPaidBy paidBy;

    private boolean hasReceipt;

    /** Required when {@code hasReceipt} is false. "No receipt" is a valid answer, an unexplained one is not. */
    @Size(max = 200)
    private String noReceiptReason;

    @Size(max = 60)
    private String referenceNumber;

    // ── Tax block — optional, shown by the form only when the type usually carries GST ──

    @Size(max = 15)
    private String supplierGstin;

    private BigDecimal taxableValue;
    private BigDecimal gstRate;
    private BigDecimal gstAmount;

    /** Accountant's call, not derivable from the type: fuel carries GST but the credit is blocked. */
    private boolean itcEligible;

    /** Defaults per type via {@code FleetTaxCharacter.defaultFor} when omitted. */
    private FleetTaxCharacter taxCharacter;

    @Size(max = 300)
    private String description;

    private String notes;
}
