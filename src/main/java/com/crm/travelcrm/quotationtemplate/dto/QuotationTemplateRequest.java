package com.crm.travelcrm.quotationtemplate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Create/update payload for a package template. */
@Data
public class QuotationTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 5000)
    private String description;

    @Size(max = 500)
    private String coverImageUrl;

    /** Defaults to true. Inactive templates stay listable but never appear in match results. */
    private Boolean active;

    /** Omit to have it derived from the sum of the itinerary's nights. */
    @Min(value = 1, message = "Duration must be at least 1 night")
    @Max(value = 365, message = "Duration must not exceed 365 nights")
    private Integer durationNights;

    @Min(value = 1, message = "Hotel tier must be between 1 and 5")
    @Max(value = 5, message = "Hotel tier must be between 1 and 5")
    private Integer hotelTier;

    @DecimalMin(value = "0.0", inclusive = false, message = "Base price must be greater than 0")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal basePrice;

    /** Months the package is sold in. Leave empty for a year-round package. */
    private Set<@Min(1) @Max(12) Integer> seasonMonths;

    @Valid
    private List<ItineraryDay> itinerary;

    @Valid
    private List<HotelItem> hotels;

    private List<String> inclusions;
    private List<String> exclusions;

    @Data
    public static class ItineraryDay {
        @NotNull(message = "dayNumber is required")
        @Min(1)
        private Integer dayNumber;

        /** Master City id. Validated tenant-owned; only ids make destination scoring reliable. */
        private Long cityId;

        @Size(max = 120) private String cityName;
        @Size(max = 120) private String destinationName;

        @Min(0) private Integer nights;

        @Size(max = 200) private String title;
        private String description;

        @DecimalMin("0.0") @Digits(integer = 13, fraction = 2)
        private BigDecimal pricePerPax;
    }

    @Data
    public static class HotelItem {
        @Size(max = 200) private String name;
        @Size(max = 120) private String city;

        @Min(1) @Max(5) private Integer stars;

        @Size(max = 80) private String roomType;
        @Size(max = 80) private String mealPlan;
        private Boolean refundable;

        @DecimalMin("0.0") @Digits(integer = 13, fraction = 2)
        private BigDecimal pricePerRoom;

        /** Omit to use however many rooms the lead needs. */
        @Min(1) private Integer rooms;

        @Min(1) private Integer nights;

        @Size(max = 500) private String imagePath;
    }
}