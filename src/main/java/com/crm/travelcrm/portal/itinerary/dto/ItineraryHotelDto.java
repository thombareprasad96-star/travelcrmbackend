package com.crm.travelcrm.portal.itinerary.dto;

import lombok.Builder;
import lombok.Data;

/** A hotel stay — traveler-safe (NO pricePerRoom/rooms). */
@Data
@Builder
public class ItineraryHotelDto {
    private String name;
    private String city;
    private Integer stars;
    private String roomType;
    private String mealPlan;
    private String checkIn;
    private String checkOut;
    private Boolean refundable;
    private String imagePath;
}