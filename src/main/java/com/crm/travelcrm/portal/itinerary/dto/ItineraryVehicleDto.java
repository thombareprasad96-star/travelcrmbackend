package com.crm.travelcrm.portal.itinerary.dto;

import lombok.Builder;
import lombok.Data;

/** A transfer/vehicle line — traveler-safe (NO price/pricePerVehicle). */
@Data
@Builder
public class ItineraryVehicleDto {
    private String type;
    private String pickup;
    private String drop;
    private String startDate;
    private String endDate;
}