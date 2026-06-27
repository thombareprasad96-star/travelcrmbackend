package com.crm.travelcrm.portal.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/** Portal pay request. {@code amount} is optional — omit it to pay the full pending balance. */
@Data
public class InitiatePaymentRequest {
    @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be positive")
    private BigDecimal amount;
}
