package com.crm.travelcrm.lead.mapper;

import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class LeadMapper {

    public Lead toEntity(CreateLeadRequestDto request) {
        Lead lead = Lead.builder()
                .customerName(request.getCustomerName())
                .phone(request.getPhone())
                .email(request.getEmail().toLowerCase())
                .leadSource(request.getLeadSource())
                .leadType(request.getLeadType())
                .leadStage(request.getLeadStage())
                .assignedUser(request.getAssignedUser())
                .birthDate(request.getBirthDate())
                .travelDate(request.getTravelDate())
                .departCountry(request.getDepartCountry())
                .departCity(request.getDepartCity())
                .rooms(request.getRooms())
                .adults(request.getAdults())
                .children(request.getChildren())
                .infants(request.getInfants())
                .extraBeds(request.getExtraBeds())
                .services(request.getServices() != null
                        ? request.getServices()
                        : Collections.emptyList())
                .notes(request.getNotes())
                .build();

        if (request.getItinerary() != null) {
            request.getItinerary().forEach(itinReq -> {
                LeadItinerary itinerary = LeadItinerary.builder()
                        .destination(itinReq.getDestination())
                        .city(itinReq.getCity())
                        .nights(itinReq.getNights())
                        .dayNumber(itinReq.getDayNumber())   // ← was missing
                        .build();
                lead.addItinerary(itinerary);
            });
        }

        return lead;
    }

    public LeadResponseDto toResponse(Lead lead) {
        List<LeadResponseDto.ItineraryItem> itineraryItems =
                lead.getItinerary() == null
                        ? Collections.emptyList()
                        : lead.getItinerary().stream()
                          .map(i -> LeadResponseDto.ItineraryItem.builder()
                                    .id(i.getPublicId())           // ← was i.getId()
                                    .destination(i.getDestination())
                                    .city(i.getCity())
                                    .nights(i.getNights())
                                    .dayNumber(i.getDayNumber())   // ← was missing
                                    .build())
                          .collect(Collectors.toList());

        return LeadResponseDto.builder()
                .id(lead.getPublicId())                // ← was lead.getId()
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .email(lead.getEmail())
                .leadSource(lead.getLeadSource())
                .leadType(lead.getLeadType())
                .leadStage(lead.getLeadStage())
                .assignedUser(lead.getAssignedUser())
                .birthDate(lead.getBirthDate())
                .travelDate(lead.getTravelDate())
                .departCountry(lead.getDepartCountry())
                .departCity(lead.getDepartCity())
                .rooms(lead.getRooms())
                .adults(lead.getAdults())
                .children(lead.getChildren())
                .infants(lead.getInfants())
                .extraBeds(lead.getExtraBeds())
                .services(lead.getServices())
                .notes(lead.getNotes())
                .itinerary(itineraryItems)
                .createdAt(lead.getCreatedAt())
                .build();
    }
}