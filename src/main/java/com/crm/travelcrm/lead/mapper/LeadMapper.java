package com.crm.travelcrm.lead.mapper;

import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
                // assignedUser is resolved from assignedUserId in the service
                // (tenant-scoped lookup) and set there
                .birthDate(request.getBirthDate())
                .travelDate(request.getTravelDate())
                .estimatedValue(request.getEstimatedValue())
                .departCountry(request.getDepartCountry())
                .departCity(request.getDepartCity())
                .rooms(request.getRooms())
                .adults(request.getAdults())
                .children(request.getChildren())
                .infants(request.getInfants())
                .extraBeds(request.getExtraBeds())
                .services(request.getServices() != null
                        ? new ArrayList<>(request.getServices())
                        : new ArrayList<>())
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
                .assignedUser(toUserDto(lead.getAssignedUser()))
                .birthDate(lead.getBirthDate())
                .travelDate(lead.getTravelDate())
                .estimatedValue(lead.getEstimatedValue())
                .departCountry(lead.getDepartCountry())
                .departCity(lead.getDepartCity())
                .rooms(lead.getRooms())
                .adults(lead.getAdults())
                .children(lead.getChildren())
                .infants(lead.getInfants())
                .extraBeds(lead.getExtraBeds())
                // Copy into a plain list while the session is open — putting the
                // Hibernate PersistentBag into the DTO defers initialization to
                // Jackson, which runs after the transaction has closed.
                .services(lead.getServices() == null
                        ? Collections.emptyList()
                        : new ArrayList<>(lead.getServices()))
                .notes(lead.getNotes())
                .itinerary(itineraryItems)
                .createdAt(lead.getCreatedAt())
                .build();
    }

    // Must be called while the Hibernate session is still open (inside the
    // @Transactional service method) so the lazy proxy can be initialized.
    private UserDto toUserDto(User user) {
        if (user == null) return null;
        return new UserDto(
                user.getPublicId(),
                user.getName(),
                user.getRole().name(),
                user.getEmail());
    }
}