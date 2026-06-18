package com.crm.travelcrm.master.airline;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AirlineDto {

    Long airlineId;
    UUID publicId;

    Long cityId;
    String cityName;
    Long destinationId;
    String destinationName;

    String name;
    String status;
    String logo;
    String country;
    String fleet;
    String iata;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}