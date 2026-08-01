package com.crm.travelcrm.booking.dto.response;

import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One expense line as the API returns it. Keyed by {@code publicId} — the internal {@code Long id}
 * is never on the wire.
 *
 * <p>{@code paidAmount}, {@code outstandingAmount} and {@code paymentStatus} are the SETTLED
 * values, which may differ from what was posted: they are what the server computed, and the client
 * should render these rather than its own arithmetic.
 */
@Getter
@Builder
public class BookingExpenseResponse {

    private UUID       publicId;
    private String     category;
    /** VENDOR or INTERNAL — only INTERNAL rows are inside the booking's netProfit. */
    private ExpenseCostType costType;
    private String     description;
    private String     vendorName;

    private BigDecimal amount;
    private BigDecimal paidAmount;
    /** amount − paidAmount, computed server-side; never negative. */
    private BigDecimal outstandingAmount;

    private ExpensePaymentStatus paymentStatus;
    private String     paymentMode;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate  expenseDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate  dueDate;

    /** True when {@code dueDate} has passed and a balance is still owed. */
    private boolean    overdue;

    private String     referenceNumber;
    private String     notes;

    private String     createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
