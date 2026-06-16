package com.crm.travelcrm.vendor.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VendorRatingDTO {
    @NotNull
    @DecimalMin("0.0") @DecimalMax("5.0")
    private Double rating;
    private String review;
}