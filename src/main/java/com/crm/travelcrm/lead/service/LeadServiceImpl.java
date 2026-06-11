package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.mapper.LeadMapper;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final LeadMapper     leadMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadResponseDto createLead(CreateLeadRequestDto request) {
        Long tenantId = currentTenantId();

        log.info("Creating lead for email: {} | tenantId: {}", request.getEmail(), tenantId);

        validateNoDuplicates(request, tenantId, null);

        Lead lead = leadMapper.toEntity(request);
        // tenantId is set here so it's available immediately in this transaction;
        // TenantEntityListener also guards it on @PrePersist as a safety net.
        lead.setTenantId(tenantId);

        Lead savedLead = leadRepository.save(lead);
        log.info("Lead created | id: {} | tenantId: {}", savedLead.getPublicId(), tenantId);
        publishLeadCreatedNotification(savedLead, tenantId);
        return leadMapper.toResponse(savedLead);
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
                .map(leadMapper::toResponse);
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

        return leadMapper.toResponse(lead);
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
        lead.setAssignedUser(request.getAssignedUser());
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
        return leadMapper.toResponse(updated);
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

    private void publishLeadCreatedNotification(Lead lead, Long tenantId) {
        // Notify all TENANT_ADMIN + MANAGER of this tenant
        List<Long> recipientIds = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(
                        tenantId,
                        List.of("TENANT_ADMIN", "MANAGER"))
                .stream()
                .map(User::getId)
                .toList();

        if (recipientIds.isEmpty()) return;

        eventPublisher.publishEvent(
                NotifyEvent.builder()
                        .type("LEAD_CREATED")
                        .tenantId(tenantId)
                        .recipientUserIds(recipientIds)
                        .title("New Lead: " + lead.getCustomerName())
                        .message(lead.getLeadSource() + " lead from " + lead.getDepartCity()
                                + " assigned to " + lead.getAssignedUser())
                        .referenceType("LEAD")
                        .referencePublicId(lead.getPublicId())
                        .channels(Set.of(DeliveryChannel.IN_APP))
                        .payload(Map.of(
                                "leadId",       lead.getPublicId().toString(),
                                "customerName", lead.getCustomerName(),
                                "source",       lead.getLeadSource(),
                                "assignedTo",   lead.getAssignedUser() != null ? lead.getAssignedUser() : ""
                        ))
                        .build()
        );

        log.info("LEAD_CREATED notification published for lead {} to {} recipients",
                lead.getPublicId(), recipientIds.size());
    }

}