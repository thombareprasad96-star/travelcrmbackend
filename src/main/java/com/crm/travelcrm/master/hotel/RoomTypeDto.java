package com.crm.travelcrm.master.hotel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoomTypeDto {

    Long roomTypeId;
    UUID publicId;
    Long hotelId;
    String name;
    String size;
    Integer occupancy;
    String bedType;
    String description;
    List<String> images;
}