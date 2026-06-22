package com.crm.travelcrm.quotation.dto;

import com.crm.travelcrm.quotation.enums.DiscountType;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full quotation view. Mirrors the request structure (so the frontend can reload it
 * straight back into the builder for edit mode) and additionally exposes the
 * server-computed {@link Totals}, the customer {@link Customer} snapshot and audit
 * metadata. The internal {@code Long id} is never exposed — only {@code publicId}.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuotationResponseDto {

    private UUID publicId;
    private UUID leadId;          // Lead.publicId
    private String title;
    private String version;
    private Integer versionNumber;
    private String pdfUrl;
    private QuotationStage stage;
    private String coverImageUrl;
    private String notes;

    // Computed display helpers — derived in the mapper at read time, not stored
    private Integer quoteNo;   // per-tenant sequential quote number (shared across a version family)
    private Integer nights;    // summed from hotel check-in/out spans
    private Integer days;      // nights + 1, or sightseeing day count
    private Integer rooms;     // summed across hotels

    private Customer customer;

    private FlightSection flight;
    private HotelSection hotel;
    private SightseeingSection sightseeing;
    private CruiseSection cruise;
    private VehicleSection vehicle;
    private AddonSection addons;

    private List<String> inclusions;
    private List<String> exclusions;
    private List<String> paymentPolicies;
    private List<String> cancellationPolicies;
    private List<String> bookingTerms;

    private Pricing pricing;
    private Totals totals;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Customer snapshot ─────────────────────────────────────────────────────
    @Data
    @Builder
    public static class Customer {
        private String name;
        private String phone;
        private String email;
        private String destination;
        private LocalDate travelDate;
        private Integer adults;
        private Integer children;
        private Integer infants;
    }

    // ── Computed money breakdown ──────────────────────────────────────────────
    @Data
    @Builder
    public static class Totals {
        private BigDecimal subtotal;
        private DiscountType discountType;
        private BigDecimal discount;        // raw value entered
        private BigDecimal discountAmount;  // resolved INR amount
        private BigDecimal markup;
        private BigDecimal taxPercent;
        private BigDecimal taxAmount;
        private BigDecimal grandTotal;
        private BigDecimal addonsTotal;
        private BigDecimal perAdult;        // grandTotal / adults (null when adults unknown)
    }

    // ── Flight ────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class FlightSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String journey;
        private List<Segment> segments;
    }

    @Data
    @Builder
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
        private Integer cabin;
        private Integer checkin;
        private BigDecimal pricePerPax;
        private Integer pax;
        private List<Connection> connections;
    }

    @Data
    @Builder
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
    @Builder
    public static class HotelSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String notes;
        private List<HotelItem> hotels;
    }

    @Data
    @Builder
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

    // ── Sightseeing ───────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class SightseeingSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private String notes;
        private List<DayItem> days;
    }

    @Data
    @Builder
    public static class DayItem {
        private Integer day;
        private String date;
        private BigDecimal pricePerPax;
        private Integer pax;
        private List<Activity> activities;
    }

    @Data
    @Builder
    public static class Activity {
        private String attraction;
        private String startTime;
        private String description;
        private List<String> meals;
        private String transfer;
        private String imageUrl;
    }

    // ── Cruise ────────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class CruiseSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private List<CruiseItem> cruises;
    }

    @Data
    @Builder
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

    // ── Vehicle ───────────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class VehicleSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private List<VehicleItem> vehicles;
    }

    @Data
    @Builder
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
    @Builder
    public static class AddonSection {
        private Boolean included;
        private String title;
        private BigDecimal amount;
        private List<AddonItem> items;
    }

    @Data
    @Builder
    public static class AddonItem {
        private String serviceType;
        private String description;
        private Integer quantity;
        private BigDecimal pricePerUnit;
        private Boolean included;
    }

    // ── Pricing inputs ────────────────────────────────────────────────────────
    @Data
    @Builder
    public static class Pricing {
        private BigDecimal discount;
        private DiscountType discType;
        private BigDecimal tax;
        private BigDecimal markup;
    }
}