package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class LeadResponseDto {

    private UUID id;             // ← was Long, now UUID (exposes publicId)
    private String customerName;
    private String phone;
    private String email;
    private LeadSource leadSource;
    private LeadType leadType;
    private LeadStage leadStage;
    private UserDto assignedUser;
    private LocalDate birthDate;
    private LocalDate travelDate;
    private BigDecimal budget;
    private String departCountry;
    private String departCity;
    private Integer rooms;
    private Integer adults;
    private Integer children;
    private Integer infants;
    private Integer extraBeds;
    private List<String> services;
    private String notes;
    private List<ItineraryItem> itinerary;
    private LocalDateTime createdAt;

    /** Ref to this lead's newest quotation (null when none) — drives the "View/Download vs Create" UI. */
    private QuotationRefDto latestQuotation;

    // ── Conversion state (Lead → Booking) ─────────────────────────────────────
    // Non-null once the lead has been converted. The UI uses convertedBookingPublicId
    // to relabel the "Convert to Booking" action to "View Booking" and prevent a
    // second accidental conversion.
    private LocalDateTime convertedAt;
    private UUID convertedBookingPublicId;

    @Data
    @Builder
    public static class ItineraryItem {
        private UUID id;           // ← publicId
        private String destination;
        private String city;
        private Integer nights;
        private Integer dayNumber; // ← was missing
    }
}