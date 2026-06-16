package com.crm.travelcrm.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorEmailDTO {
    @NotBlank
    private String subject;
    @NotBlank
    private String message;
}