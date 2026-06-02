package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.CreateLeadRequest;
import com.crm.travelcrm.lead.dto.LeadResponse;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.mapper.LeadMapper;
import com.crm.travelcrm.lead.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final LeadMapper leadMapper;

    @Override
    @Transactional
    public LeadResponse createLead(CreateLeadRequest request) {
        log.info("Creating lead for email: {}", request.getEmail());
        validateNoDuplicates(request);
        Lead lead = leadMapper.toEntity(request);
        Lead savedLead = leadRepository.save(lead);
        log.info("Lead created successfully with ID: {}", savedLead.getId());
        return mapToResponse(savedLead);
    }

    @Override
    public LeadResponse searchLead(String keyword) {
        Lead lead;
        if (keyword.contains("@")) {
            lead = leadRepository.findByEmail(keyword)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Lead not found with email : " + keyword));

        } else {
            lead = leadRepository.findByPhone(keyword)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Lead not found with phone : " + keyword));
        }

        return mapToResponse(lead);
    }

    @Override
    public Page<LeadResponse> getAllLeads(
            int page,
            int size,
            String sortBy,
            String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Lead> leads = leadRepository.findAll(pageable);
        return leads.map(this::mapToResponse);
    }

    private void validateNoDuplicates(CreateLeadRequest request) {

        if (request.getEmail() != null &&
                leadRepository.existsByEmail(request.getEmail().toLowerCase())) {

            throw new DuplicateLeadException(
                    "Lead already exists with email : " + request.getEmail());
        }

        if (request.getPhone() != null &&
                leadRepository.existsByPhone(request.getPhone())) {

            throw new DuplicateLeadException(
                    "Lead already exists with phone : " + request.getPhone());
        }
    }

    private LeadResponse mapToResponse(Lead lead) {

        return LeadResponse.builder()
                .id(lead.getId())
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .email(lead.getEmail())
                .leadSource(lead.getLeadSource())
                .leadType(lead.getLeadType())
                .leadStage(lead.getLeadStage())
                .assignTo(lead.getAssignTo())
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

                .itinerary(
                        lead.getItinerary() == null
                                ? null
                                : lead.getItinerary()
                                  .stream()
                                  .map(this::mapItinerary)
                                  .toList()
                )

                .createdAt(lead.getCreatedAt())
                .build();
    }

    private LeadResponse.ItineraryItem mapItinerary(
            LeadItinerary itinerary) {

        return LeadResponse.ItineraryItem.builder()
                .id(itinerary.getId())
                .destination(itinerary.getDestination())
                .city(itinerary.getCity())
                .nights(itinerary.getNights())
                .build();
    }
}