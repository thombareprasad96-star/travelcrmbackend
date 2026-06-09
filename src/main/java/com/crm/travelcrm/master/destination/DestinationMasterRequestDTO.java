package com.crm.travelcrm.master.destination;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DestinationMasterRequestDTO {

    @NotBlank(message = "Country is required")
    private String country;

    @NotBlank(message = "Destination name is required")
    private String name;

    private String type;

    private String imagePath;

    private String inclusions;

    private String exclusions;

    private String paymentPolicies;

    private String cancellationPolicies;

    private String bookingTerms;

    private String status;
}