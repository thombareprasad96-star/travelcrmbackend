package com.crm.travelcrm.master.hotel.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class HotelResponseDTO {
    private Long id;
    private Long destinationId;
    private String name;
    private String city;
    private Integer stars;
    private Double rating;
    private Boolean isDefault;
    private String address;
    private String mapUrl;
    private Double latitude;
    private Double longitude;
    private String contactPerson;
    private String phone;
    private String email;
    private String website;
    private String overview;
    private String imageUrl;
    private List<String> amenities;
    private List<RoomTypeResponseDTO> roomTypes;
    private List<MealPlanResponseDTO> mealPlans;
}