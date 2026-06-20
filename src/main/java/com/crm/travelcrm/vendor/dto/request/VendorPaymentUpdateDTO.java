package com.crm.travelcrm.vendor.dto.request;

import com.crm.travelcrm.vendor.enums.VendorPayStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class VendorPaymentUpdateDTO {
    @NotNull
    private VendorPayStatus payStatus;
    private BigDecimal amountPaid;
}