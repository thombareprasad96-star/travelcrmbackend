package com.crm.travelcrm.quotation.dto;

import com.crm.travelcrm.quotation.enums.DiscountType;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Single create/update payload for a quotation — mirrors {@code buildQuotationPayload}
 * in the frontend {@code quotationService.js}. Every tab is sent together.
 *
 * <p>Unknown / absent fields are tolerated (Jackson is configured to ignore unknown
 * properties), so the slightly richer per-item pricing fields the newer tabs compute
 * (e.g. {@code pricePerPax}, {@code pricePerRoom}) are captured when present without
 * breaking the older flat contract.
 */
@Data
public class QuotationRequestDto {

    /** Lead.publicId (UUID) the quotation is being prepared for. */
    private UUID leadId;

    @Size(max = 200, message = "Title must not exceed 200 characters")
    private String title;

    @Size(max = 30)
    private String version;

    private QuotationStage quotationStage;

    @Size(max = 500)
    private String coverImageUrl;

    @Size(max = 5000)
    private String notes;

    @Valid private FlightSection flight;
    @Valid private HotelSection hotel;
    @Valid private SightseeingSection sightseeing;
    @Valid private CruiseSection cruise;
    @Valid private VehicleSection vehicle;
    @Valid private AddonSection addons;

    private List<String> inclusions;
    private List<String> exclusions;
    private List<String> paymentPolicies;
    private List<String> cancellationPolicies;
    private List<String> bookingTerms;

    @Valid private Pricing pricing;

    // ── Flight ────────────────────────────────────────────────────────────────
    @Data
    public static class FlightSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String journey;
        @Valid private List<Segment> segments;
    }

    @Data
    public static class Segment {
        private String airline;
        private String flightNo;
        @JsonProperty("class") private String travelClass;
        private String from;
        private String to;
        private String depDate;
        private String depTime;
        private String arrDate;
        private String arrTime;
        private String duration;
        private Integer cabin;     // cabin baggage (kg)
        private Integer checkin;   // check-in baggage (kg)
        private BigDecimal pricePerPax;
        private Integer pax;
        @Valid private List<Connection> connections;
    }

    @Data
    public static class Connection {
        private String airline;
        private String flightNo;
        private String from;
        private String to;
        private String depDate;
        private String depTime;
        private String arrDate;
        private String arrTime;
    }

    // ── Hotel ─────────────────────────────────────────────────────────────────
    @Data
    public static class HotelSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String notes;
        @Valid private List<HotelItem> hotels;
    }

    @Data
    public static class HotelItem {
        private String name;
        private String city;
        private String checkIn;
        private String checkOut;
        private String roomType;
        private String mealPlan;
        private Boolean refundable;
        private Integer stars;
        private BigDecimal pricePerRoom;
        private Integer rooms;
        private String imageUrl;
    }

    // ── Sightseeing ─────────────────────────────────────────────────────────--
    @Data
    public static class SightseeingSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String notes;
        @Valid private List<DayItem> days;
    }

    @Data
    public static class DayItem {
        private Integer day;
        private String date;
        private BigDecimal pricePerPax;
        private Integer pax;
        @Valid private List<Activity> activities;
    }

    @Data
    public static class Activity {
        private String attraction;
        private String startTime;
        private String description;
        private List<String> meals;
        private String transfer;
        private String imageUrl;
    }

    // ── Cruise ──────────────────────────────────────────────────────────────--
    @Data
    public static class CruiseSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        @Valid private List<CruiseItem> cruises;
    }

    @Data
    public static class CruiseItem {
        private String name;
        private String type;
        private String depPort;
        private String arrPort;
        private String depDate;
        private Integer nights;
        private String cabin;
        private BigDecimal price;
        private BigDecimal pricePerPax;
        private Integer pax;
    }

    // ── Vehicle ─────────────────────────────────────────────────────────────--
    @Data
    public static class VehicleSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        @Valid private List<VehicleItem> vehicles;
    }

    @Data
    public static class VehicleItem {
        private String type;
        private String pickup;
        private String drop;
        private String startDate;
        private String endDate;
        private BigDecimal price;
        private BigDecimal pricePerVehicle;
        private Integer qty;
        private String notes;
    }

    // ── Add-on services ───────────────────────────────────────────────────────
    @Data
    public static class AddonSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        @Valid private List<AddonItem> items;
    }

    @Data
    public static class AddonItem {
        private String serviceType;
        private String description;
        private Integer quantity;
        private BigDecimal pricePerUnit;
        private Boolean included;
    }

    // ── Pricing / adjustments ─────────────────────────────────────────────────
    @Data
    public static class Pricing {
        private BigDecimal discount;
        private DiscountType discType;
        private BigDecimal tax;
        private BigDecimal markup;
    }
}