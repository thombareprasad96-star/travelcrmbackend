package com.crm.travelcrm.accounting.tds.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class VendorPaymentResponse {

    private final UUID publicId;
    private final BigDecimal amount;
    private final BigDecimal tdsWithheld;
    private final LocalDate paymentDate;
    private final String paymentMethod;
    private final String reference;
    private final String notes;
    private final LocalDateTime createdAt;
}