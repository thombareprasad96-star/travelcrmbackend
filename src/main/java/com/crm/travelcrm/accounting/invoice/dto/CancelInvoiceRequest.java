package com.crm.travelcrm.accounting.invoice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Void an issued invoice. The number is retained and reported as cancelled — never reused. */
@Getter
@Setter
public class CancelInvoiceRequest {

    @NotBlank
    @Size(max = 500)
    private String reason;
}