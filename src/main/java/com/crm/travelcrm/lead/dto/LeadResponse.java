// ─── LeadResponse.java ───────────────────────────────────────────────────────
package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class LeadResponse {

    private Long id;
    private String customerName;
    private String phone;
    private String email;
    private LeadSource leadSource;
    private LeadType leadType;
    private LeadStage leadStage;
    private String assignTo;
    private LocalDate birthDate;
    private LocalDate travelDate;
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

    @Data
    @Builder
    public static class ItineraryItem {
        private Long id;
        private String destination;
        private String city;
        private Integer nights;
    }
}