package com.crm.travelcrm.master.hotel;

import lombok.Data;

import java.util.List;

@Data
public class UpdateHotelRequest {

    private String name;
    private Long destinationId;
    private String city;
    private Integer stars;
    private Double rating;
    private String address;
    private String contactPerson;
    private String phone;
    private String email;
    private String website;
    private String mapUrl;
    private Double latitude;
    private Double longitude;
    private String overview;
    private List<String> amenities;
    private Boolean isDefault;
    private String imagePath;
}