package com.crm.travelcrm.master.hotel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HotelDto {

    Long hotelId;
    UUID publicId;

    Long cityId;
    String city;
    Long destinationId;
    String destinationName;
    Long countryId;
    String countryName;

    String name;
    Integer stars;
    Double rating;
    String address;
    String contactPerson;
    String phone;
    String email;
    String website;
    String mapUrl;
    Double latitude;
    Double longitude;
    String overview;
    List<String> amenities;
    boolean isDefault;
    String imagePath;

    List<RoomTypeDto> roomTypes;
    List<MealPlanDto> mealPlans;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}