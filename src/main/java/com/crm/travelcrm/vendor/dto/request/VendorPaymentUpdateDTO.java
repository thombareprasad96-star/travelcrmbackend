package com.crm.travelcrm.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VendorPaymentUpdateDTO {
    @NotBlank
    private String payStatus;
    private BigDecimal amountPaid;
}