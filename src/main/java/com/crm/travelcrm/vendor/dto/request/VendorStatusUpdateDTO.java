package com.crm.travelcrm.vendor.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VendorStatusUpdateDTO {
    @NotBlank
    private String status;
}