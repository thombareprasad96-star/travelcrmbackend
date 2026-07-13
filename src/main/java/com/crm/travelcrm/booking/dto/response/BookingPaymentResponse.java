package com.crm.travelcrm.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class BookingPaymentResponse {

    private UUID          publicId;
    private BigDecimal    amount;
    /** RECEIPT (money in) or REFUND (money out) — lets the ledger/invoice render the two distinctly. */
    private String        entryType;
    private String        paymentType;
    private String        paymentMethod;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate     paymentDate;

    private String        reference;
    private String        notes;

    /** The service line this receipt is attributed to, if any. */
    private UUID          serviceItemPublicId;

    private String        createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}