package com.crm.travelcrm.master.sightseeing;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSightseeingRequest {

    private String destination;
    private String city;

    @NotBlank
    private String title;

    private Integer sequence;
    private Double estimatedHours;
    private String suggestedStartTime;
    private String imagePath;
    private String description;
    private String remarks;
}