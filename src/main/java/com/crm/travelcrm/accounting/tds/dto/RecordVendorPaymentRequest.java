package com.crm.travelcrm.accounting.tds.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Record a disbursement against a vendor bill. */
@Getter
@Setter
public class RecordVendorPaymentRequest {

    @NotNull
    @Positive
    private BigDecimal amount;

    private LocalDate paymentDate;

    private String paymentMethod;

    private String reference;

    private String notes;

    @Size(max = 80)
    private String idempotencyKey;
}