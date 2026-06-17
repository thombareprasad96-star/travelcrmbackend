package com.crm.travelcrm.master.geography.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

/** Read model for a city, including light references up the geography chain. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CityDto {

    Long cityId;
    UUID publicId;

    Long destinationId;
    String destinationName;
    Long countryId;
    String countryName;

    String name;
    String state;
    Double latitude;
    Double longitude;
    String imageUrl;
    Integer daysToStay;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}