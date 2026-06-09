package com.crm.travelcrm.lead.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeadItineraryRequestDto {

    @NotBlank(message = "Destination is required")
    @Size(max = 100, message = "Destination must not exceed 100 characters")
    private String destination;

    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City must not exceed 100 characters")
    private String city;

    @NotNull(message = "Nights is required")
    @Min(value = 1, message = "Nights must be at least 1")
    private Integer nights;

    @Min(value = 1, message = "Day number must be at least 1")
    private Integer dayNumber;   // ← optional, sequence order in itinerary
}