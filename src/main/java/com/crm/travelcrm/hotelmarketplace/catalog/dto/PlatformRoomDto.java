package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/** A catalog room category. Identical for both audiences — a room carries no commercial data. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformRoomDto {

    UUID publicId;
    String name;
    Integer maxAdults;
    Integer maxChildren;
    Integer maxOccupancy;
    String bedType;
    String size;
    String description;
    boolean active;
    List<String> images;
}
