package com.crm.travelcrm.master.hotel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HotelRequestDTO {

    @NotNull(message = "Destination is required")
    private Long destinationId;

    @NotBlank(message = "Hotel name is required")
    private String name;

    @NotBlank(message = "City is required")
    private String city;

    @NotNull(message = "Star category is required")
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

    private List<String> amenities = new ArrayList<>();
    private List<RoomTypeRequestDTO> roomTypes = new ArrayList<>();
    private List<MealPlanRequestDTO> mealPlans = new ArrayList<>();
}