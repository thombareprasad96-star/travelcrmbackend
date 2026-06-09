package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.mapper.LeadMapper;
import com.crm.travelcrm.lead.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final LeadMapper     leadMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadResponseDto createLead(CreateLeadRequestDto request) {
        Long tenantId = currentTenantId();

        log.info("Creating lead for email: {} | tenantId: {}", request.getEmail(), tenantId);

        validateNoDuplicates(request, tenantId, null);

        Lead lead = leadMapper.toEntity(request);
        lead.setTenantId(tenantId);
        // tenantId auto-set by TenantEntityListener — do NOT set manually

        Lead savedLead = leadRepository.save(lead);
        log.info("Lead created | id: {} | tenantId: {}", savedLead.getPublicId(), tenantId);
        return mapToResponse(savedLead);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<LeadResponseDto> getAllLeads(int page, int size,
                                             String sortBy, String sortDir) {
        Long tenantId = currentTenantId();

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        // Scoped to tenant + excludes soft-deleted records
        return leadRepository
                .findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable)
                .map(leadMapper::toResponse);   // mapping inside @Transactional — session open ✅
    }

    @Override
    @Transactional(readOnly = true)
    public LeadResponseDto searchLead(String keyword) {
        Long tenantId = currentTenantId();
        Lead lead;

        if (keyword.contains("@")) {
            lead = leadRepository
                    .findByEmailAndTenantIdAndDeletedAtIsNull(keyword, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Lead not found with email: " + keyword));
        } else {
            lead = leadRepository
                    .findByPhoneAndTenantIdAndDeletedAtIsNull(keyword, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Lead not found with phone: " + keyword));
        }

        return mapToResponse(lead);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadResponseDto updateLead(UUID publicId, CreateLeadRequestDto request) {
        Long tenantId = currentTenantId();

        // Tenant-scoped fetch — cannot update another tenant's lead
        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lead not found: " + publicId));

        // Duplicate check excluding self
        validateNoDuplicates(request, tenantId, publicId);

        // Basic details
        lead.setCustomerName(request.getCustomerName());
        lead.setEmail(request.getEmail());
        lead.setPhone(request.getPhone());
        lead.setLeadType(request.getLeadType());
        lead.setLeadSource(request.getLeadSource());
        lead.setLeadStage(request.getLeadStage());
        lead.setNotes(request.getNotes());

        // Assignment & personal
        lead.setAssignTo(request.getAssignTo());
        lead.setBirthDate(request.getBirthDate());

        // Travel details
        lead.setTravelDate(request.getTravelDate());
        lead.setDepartCountry(request.getDepartCountry());
        lead.setDepartCity(request.getDepartCity());

        // Passenger counts
        lead.setRooms(request.getRooms());
        lead.setAdults(request.getAdults());
        lead.setChildren(request.getChildren());
        lead.setInfants(request.getInfants());
        lead.setExtraBeds(request.getExtraBeds());

        // Services
        lead.setServices(request.getServices());

        // Itinerary — clear and rebuild
        if (request.getItinerary() != null) {
            lead.getItinerary().clear();
            request.getItinerary().forEach(item -> {
                LeadItinerary itinerary = new LeadItinerary();
                itinerary.setDestination(item.getDestination());
                itinerary.setCity(item.getCity());
                itinerary.setNights(item.getNights());
                itinerary.setDayNumber(item.getDayNumber());
                itinerary.setLead(lead);
                lead.getItinerary().add(itinerary);
            });
        }

        Lead updated = leadRepository.save(lead);
        log.info("Lead updated | publicId: {} | tenantId: {}", publicId, tenantId);
        return mapToResponse(updated);
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteLead(UUID publicId) {
        Long tenantId = currentTenantId();

        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lead not found: " + publicId));

        // Soft delete — uses BaseEntity.softDelete()
        lead.softDelete(currentUserEmail());

        leadRepository.save(lead);
        log.info("Lead soft-deleted | publicId: {} | tenantId: {}", publicId, tenantId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolve tenantId from TenantContext (set by JwtAuthFilter).
     * Fail fast if missing — means filter chain is misconfigured.
     */
    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running " +
                            "and the JWT contains a tenantId claim.");
        }
        return tenantId;
    }

    /**
     * Get logged-in user's email for audit trail (softDelete, etc.)
     */
    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /**
     * @param excludePublicId pass null on create, pass lead's publicId on update
     *                        so a lead doesn't conflict with itself
     */
    private void validateNoDuplicates(CreateLeadRequestDto request,
                                      Long tenantId,
                                      UUID excludePublicId) {

        if (request.getEmail() != null) {
            boolean emailTaken = excludePublicId == null
                    ? leadRepository.existsByEmailAndTenantIdAndDeletedAtIsNull(
                    request.getEmail().toLowerCase(), tenantId)
                    : leadRepository.existsByEmailAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
                    request.getEmail().toLowerCase(), tenantId, excludePublicId);

            if (emailTaken) throw new DuplicateLeadException(
                    "Lead already exists with email: " + request.getEmail());
        }

        if (request.getPhone() != null) {
            boolean phoneTaken = excludePublicId == null
                    ? leadRepository.existsByPhoneAndTenantIdAndDeletedAtIsNull(
                    request.getPhone(), tenantId)
                    : leadRepository.existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
                    request.getPhone(), tenantId, excludePublicId);

            if (phoneTaken) throw new DuplicateLeadException(
                    "Lead already exists with phone: " + request.getPhone());
        }
    }

    private LeadResponseDto mapToResponse(Lead lead) {
        return LeadResponseDto.builder()
                .id(lead.getPublicId())           // ← publicId, never internal Long id
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
                        lead.getItinerary() == null ? null :
                                lead.getItinerary().stream()
                                .map(this::mapItinerary)
                                .toList()
                )
                .createdAt(lead.getCreatedAt())
                .build();
    }

    private LeadResponseDto.ItineraryItem mapItinerary(LeadItinerary item) {
        return LeadResponseDto.ItineraryItem.builder()
                .id(item.getPublicId())           // ← publicId here too
                .destination(item.getDestination())
                .city(item.getCity())
                .nights(item.getNights())
                .dayNumber(item.getDayNumber())
                .build();
    }
}