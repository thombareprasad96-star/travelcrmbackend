package com.crm.travelcrm.quotationtemplate.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A package template as the API speaks it. {@code id} is the publicId — never the internal Long. */
@Data
@Builder
public class QuotationTemplateResponse {

    private UUID id;
    private String name;
    private String description;
    private String coverImageUrl;
    private Boolean active;

    private Integer durationNights;
    private Integer durationDays;
    private Integer hotelTier;
    private BigDecimal basePrice;

    /** Empty ⇒ year-round. */
    private List<Integer> seasonMonths;

    /** Distinct city names in itinerary order — the chips on the template card. */
    private List<String> cities;

    private List<ItineraryDay> itinerary;
    private List<HotelItem> hotels;
    private List<String> inclusions;
    private List<String> exclusions;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class ItineraryDay {
        private UUID id;
        private Integer dayNumber;
        private Long cityId;
        private String cityName;
        private String destinationName;
        private Integer nights;
        private String title;
        private String description;
        private BigDecimal pricePerPax;
    }

    @Data
    @Builder
    public static class HotelItem {
        private UUID id;
        private Integer sortOrder;
        private String name;
        private String city;
        private Integer stars;
        private String roomType;
        private String mealPlan;
        private Boolean refundable;
        private BigDecimal pricePerRoom;
        private Integer rooms;
        private Integer nights;
        private String imagePath;
    }
}