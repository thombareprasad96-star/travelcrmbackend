package com.crm.travelcrm.vendor.dto.request;

import com.crm.travelcrm.vendor.enums.VendorStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VendorStatusUpdateDTO {
    @NotNull
    private VendorStatus status;
}