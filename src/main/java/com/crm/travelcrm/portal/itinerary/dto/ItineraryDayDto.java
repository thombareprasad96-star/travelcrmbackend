package com.crm.travelcrm.portal.itinerary.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A single day of the sightseeing timeline. No pricePerPax/pax exposed. */
@Data
@Builder
public class ItineraryDayDto {
    private Integer dayNumber;
    private String date;
    private List<ItineraryActivityDto> activities;
}