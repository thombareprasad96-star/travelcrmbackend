package com.crm.travelcrm.portal.itinerary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** One sightseeing activity — traveler-safe (no pricing/pax). */
@Data
@Builder
public class ItineraryActivityDto {
    private String attraction;
    private String startTime;
    private String description;
    private String transfer;
    private List<String> meals;
    private String imagePath;
}