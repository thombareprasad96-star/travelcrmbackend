package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateDestinationRequest {

    @Size(max = 150)
    private String name;

    private String description;

    @Size(max = 50)
    private String type;

    @Size(max = 500)
    private String imagePath;           // ← was imageUrl

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal price;

    private String inclusions;
    private String exclusions;
    private String paymentPolicies;
    private String cancellationPolicies;
    private String bookingTerms;

    @Size(max = 20)
    private String status;
}