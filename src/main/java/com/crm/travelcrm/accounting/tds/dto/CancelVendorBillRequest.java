package com.crm.travelcrm.accounting.tds.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Cancel a vendor bill (only allowed before any payment is disbursed). */
@Getter
@Setter
public class CancelVendorBillRequest {

    @NotBlank
    @Size(max = 500)
    private String reason;
}