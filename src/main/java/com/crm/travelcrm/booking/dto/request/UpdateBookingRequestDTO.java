// ── UpdateBookingRequestDTO ───────────────────────────────────────────────────
//
// All fields are OPTIONAL for a patch operation.
// Null = "don't change this field" (enforced by MapStruct IGNORE strategy)
// Only fields the client is permitted to change are present here.

package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.BookingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class UpdateBookingRequestDTO {

    // All fields nullable — partial update pattern
    // Validation only runs when the field is present

    private String destination;

    /**
     * Travel date. Deliberately NOT {@code @FutureOrPresent}, unlike the create DTO.
     *
     * <p>A booking spends most of its life with a travel date in the past — mid-trip, or travelled
     * and awaiting final settlement — and the edit form round-trips every field it loaded. Validating
     * this the way create does made a booking permanently uneditable the day its travel date passed:
     * the amount could no longer be corrected, the assignee could not be changed, services could not
     * be added. The rule belongs on create, where "you cannot schedule a trip into the past" is
     * actually true; here it only ever rejected an unchanged value.
     *
     * <p>Moving a date backwards remains a client-side concern — the form still refuses a date the
     * clerk edits into the past, and leaves an untouched one alone.
     */
    private LocalDate travelDate;

    private LocalDate bookingDate;

    @DecimalMin(value = "0.0", inclusive = false,
            message = "Customer amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal customerAmount;

    // Inclusive of zero, matching the create contract: an agent who booked no supplier through the
    // booking itself — because the spend is itemised in the expense ledger — must be able to say so.
    // The exclusive minimum made clearing a mistyped figure impossible; the only way down was 0.01.
    @DecimalMin(value = "0.0", message = "Vendor cost cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    /**
     * Re-point the booking at a different supplier, by {@code publicId}. Null leaves it unchanged,
     * per this DTO's patch semantics — so a client that never sends the field cannot accidentally
     * unlink the vendor. Resolved tenant-scoped; an unknown id is a 404.
     */
    private java.util.UUID vendorPublicId;

    /**
     * Explicitly UNLINK the supplier. Exists because {@link #vendorPublicId} cannot express it: under
     * this DTO's patch contract null means "leave alone", so an emptied vendor dropdown would
     * otherwise be indistinguishable from a request that simply never mentioned the field — and the
     * booking would silently keep a supplier the user had removed.
     *
     * <p>Wins over {@link #vendorPublicId} when both are sent, so a confused client unlinks rather
     * than re-links. Pair it with {@code vendorCost = 0}: a booking with no supplier owes no
     * supplier money, and the server does not infer that for you.
     */
    private Boolean clearVendor;

    /**
     * Reclassify the booking as an overseas tour package (or back). Null leaves it unchanged, per
     * this DTO's patch semantics. Changing it re-runs the tax computation, so it can move
     * {@code tcs} and {@code totalPayable} when the tenant's policy is OVERSEAS_ONLY.
     */
    private Boolean overseasTourPackage;

    private List<String> services;

    /** Editable traveller, departure, itinerary, and trip-note snapshot. */
    @Valid
    private TripSnapshotRequest tripSnapshot;

    /** Optional assignee change, resolved tenant-safely by public UUID. */
    private UUID assignedUserId;

    // Amount already collected — ABSOLUTE value (not an increment). Server re-derives
    // paymentStatus / pendingAmount and rejects a value above total payable.
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount;

    // Booking status. Applied with lifecycle guards in the service: CANCELLED is refused
    // here (use the cancel action so the lead/customer are handled); a COMPLETED/CANCELLED
    // booking is terminal and locked.
    private BookingStatus status;

    // ── Still server-owned / immutable (intentionally NOT editable here) ───────
    // customerName  → resolved server-side snapshot, never client-supplied
    // customerId    → immutable after creation (would break referential integrity)
    // bookingCode   → immutable business code
}
