package com.crm.travelcrm.master.sightseeing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SightseeingDto {

    Long sightseeingId;
    UUID publicId;

    Long cityId;
    String city;
    Long destinationId;
    String destination;
    Long countryId;
    String countryName;

    String title;
    Integer sequence;
    Double estimatedHours;
    String suggestedStartTime;
    String imagePath;
    String description;
    String remarks;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}