package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums  .ExpensePaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edits one expense line at {@code PUT /api/bookings/{publicId}/expenses/{expensePublicId}} —
 * correcting a typo'd figure, or recording that a credit line has since been settled.
 *
 * <p>Patch semantics, matching {@link UpdateBookingServiceItemRequest}: every field is optional and
 * {@code null} means "leave unchanged". The consequence is that a field cannot be CLEARED back to
 * null through this endpoint; that is the sibling's established trade-off and is the right one
 * here, since the alternative silently wipes a vendor name or a reference number the moment a
 * caller omits it.
 *
 * <p><b>Money is re-settled as a unit.</b> Whichever of {@code amount} / {@code paidAmount} /
 * {@code paymentStatus} are supplied, the service merges them over the row's current values and
 * re-runs the whole triple through {@code ExpenseSettlementCalculator}. So raising the amount on a
 * line that was PAID leaves it PAID and settled in full, while lowering it below what was already
 * disbursed is rejected rather than quietly clamped.
 *
 * <p>Sending {@code paidAmount} alone is enough to record a disbursement — it is taken at face
 * value and the status is derived from it, so settling ₹4,000 of a ₹10,000 credit line is just
 * {@code {"paidAmount": 4000}} and comes back PARTIAL. You never have to restate the status to
 * make a payment stick; see {@code BookingExpenseServiceImpl.mergeIntent} for the precedence.
 */
@Getter
@Setter
public class UpdateBookingExpenseRequest {

    @Size(max = 60, message = "Category is too long")
    private String category;

    /**
     * Reclassify the line between supplier cost and agency overhead. Null leaves it unchanged, per
     * this DTO's patch semantics. Changing it moves the amount into or out of the booking's
     * {@code totalInternalCosts}, so the service recalculates profit on any change.
     */
    private ExpenseCostType costType;

    @Size(max = 300, message = "Expense details cannot exceed 300 characters")
    private String description;

    @Size(max = 200, message = "Vendor name is too long")
    private String vendorName;

    @DecimalMin(value = "0.01", message = "Expense amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    /** Re-read as intent, exactly as on create: CREDIT zeroes the paid amount, PAID fills it. */
    private ExpensePaymentStatus paymentStatus;

    @Size(max = 40, message = "Payment mode is too long")
    private String paymentMode;

    private LocalDate expenseDate;

    private LocalDate dueDate;

    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount;

    @Size(max = 120, message = "Reference number is too long")
    private String referenceNumber;

    @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
    private String notes;
}
