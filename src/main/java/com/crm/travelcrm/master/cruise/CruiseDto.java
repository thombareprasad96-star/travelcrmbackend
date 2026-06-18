package com.crm.travelcrm.master.cruise;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CruiseDto {

    Long cruiseId;
    UUID publicId;

    Long cityId;
    String cityName;
    Long destinationId;
    String destinationName;

    String name;
    String description;
    String image;
    List<CruiseRoomTypeDto> roomTypes;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}