package com.crm.travelcrm.master.sightseeing;

import lombok.Data;

@Data
public class UpdateSightseeingRequest {

    private String destination;
    private String city;
    private String title;
    private Integer sequence;
    private Double estimatedHours;
    private String suggestedStartTime;
    private String imagePath;
    private String description;
    private String remarks;
}