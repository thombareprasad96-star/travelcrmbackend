package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One cost line on a booking. Sent as an element of {@link BulkCreateBookingExpensesRequest} to
 * {@code POST /api/bookings/{publicId}/expenses}.
 *
 * <p>Field names and nullability mirror the expense form exactly: {@code vendorName},
 * {@code paymentMode}, {@code dueDate}, {@code referenceNumber} and {@code notes} arrive as an
 * explicit {@code null} when the input was left blank, so none of them may be {@code @NotBlank}.
 *
 * <p>{@code category} and {@code paymentMode} arrive as the form's Title-case display strings
 * ("Office Expense", "Bank Transfer") and are stored verbatim — they are free text on the entity,
 * not enums, so no case coercion is applied or needed.
 */
@Getter
@Setter
public class CreateBookingExpenseRequest {

    /**
     * Free-text label ("Hotel", "Commission", "Office Expense"). Optional: an internal-cost row
     * posted without one is filed under "Other", so a client following the
     * {@code title}/{@code amount}/{@code notes}/{@code costDate} contract need not know this field
     * exists.
     */
    @Size(max = 60, message = "Category is too long")
    private String category;

    /**
     * VENDOR (supplier cost) or INTERNAL (the agency's own overhead). Optional: omitting it stores
     * VENDOR, which keeps the line out of {@code netProfit} — the safe default, and what every row
     * written before this field existed is. Only INTERNAL rows reduce the booking's profit.
     */
    private ExpenseCostType costType;

    /**
     * What the money was for. Accepts {@code "title"} as an alias so a client written against the
     * internal-cost contract ({@code title}/{@code amount}/{@code notes}/{@code costDate}) works
     * unchanged — this ledger predates that contract and calls the same field {@code description},
     * and renaming it would break the existing expense screen.
     */
    @NotBlank(message = "Expense details are required")
    @Size(max = 300, message = "Expense details cannot exceed 300 characters")
    @JsonAlias("title")
    private String description;

    @Size(max = 200, message = "Vendor name is too long")
    private String vendorName;

    @NotNull(message = "Expense amount is required")
    @DecimalMin(value = "0.01", message = "Expense amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    /**
     * The caller's INTENT, not a stored value. CREDIT forces paidAmount to 0 and PAID forces it to
     * the full amount; the status actually persisted is derived back from the settled paidAmount
     * by {@code ExpenseSettlementCalculator}.
     *
     * <p>Omitting it is read as PARTIAL — "take the posted {@link #paidAmount} at face value" —
     * NOT as CREDIT. The end result is the same for a caller who omits both (no paid amount
     * derives back to CREDIT), but a caller who posts a paidAmount without a status has it
     * honoured rather than zeroed.
     */
    private ExpensePaymentStatus paymentStatus;

    @Size(max = 40, message = "Payment mode is too long")
    private String paymentMode;

    /** The date the cost was incurred. {@code "costDate"} is accepted as an alias — see description. */
    @NotNull(message = "Expense date is required")
    @JsonAlias("costDate")
    private LocalDate expenseDate;

    /** Optional. Rejected when earlier than {@link #expenseDate}. */
    private LocalDate dueDate;

    /**
     * How much has been disbursed so far. Honoured only when {@code paymentStatus} is PARTIAL (or
     * absent); CREDIT and PAID override it. A value greater than {@link #amount} is rejected
     * rather than clamped — see {@code ExpenseSettlementCalculator}.
     */
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount;

    @Size(max = 120, message = "Reference number is too long")
    private String referenceNumber;

    @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
    private String notes;

    // NOTE — the form also posts `outstandingAmount`, computed in the browser. It is deliberately
    // absent from this DTO: the balance is derived server-side from amount − paidAmount and is not
    // even a column (BookingExpense.getOutstandingAmount() is @Transient). Jackson is not
    // configured to fail on unknown properties, so the extra field is ignored on the way in
    // rather than 400-ing the request.
}
