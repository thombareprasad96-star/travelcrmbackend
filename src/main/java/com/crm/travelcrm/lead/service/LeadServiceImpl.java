package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.event.LeadSoftDeletedEvent;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadBoardColumnDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.mapper.LeadMapper;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.crm.travelcrm.quotation.service.QuotationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final LeadMapper     leadMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final QuotationService quotationService;

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
        lead.setAssignedUser(resolveAssignedUser(request.getAssignedUserId(), tenantId));

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
        Page<Lead> leadPage = leadRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);

        List<LeadResponseDto> dtos = leadPage.getContent().stream()
                .map(leadMapper::toResponse)
                .collect(Collectors.toList());
        // One batch query for the latest quotation of every lead on this page (no N+1).
        enrichWithLatestQuotation(dtos);

        return new PageImpl<>(dtos, pageable, leadPage.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public LeadResponseDto getLeadById(UUID publicId) {
        Long tenantId = currentTenantId();

        // Tenant-scoped fetch by publicId — never expose the internal Long id
        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lead not found: " + publicId));

        LeadResponseDto dto = leadMapper.toResponse(lead);
        dto.setLatestQuotation(quotationService.getLatestRefByLead(dto.getId()));
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public LeadResponseDto searchLead(String keyword) {
        Long tenantId = currentTenantId();
        Lead lead;

        if (keyword.contains("@")) {
            lead = leadRepository
                    .findByEmailAndTenantIdAndDeletedAtIsNull(keyword.toLowerCase(), tenantId)
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
        // Lowercase to match create + duplicate checks — otherwise an updated
        // email can dodge the tenant-scoped uniqueness validation
        lead.setEmail(request.getEmail().toLowerCase());
        lead.setPhone(request.getPhone());
        lead.setLeadType(request.getLeadType());
        lead.setLeadSource(request.getLeadSource());
        lead.setLeadStage(request.getLeadStage());
        lead.setNotes(request.getNotes());

        // Assignment & personal
        lead.setAssignedUser(resolveAssignedUser(request.getAssignedUserId(), tenantId));
        lead.setBirthDate(request.getBirthDate());

        // Travel details
        lead.setTravelDate(request.getTravelDate());
        lead.setEstimatedValue(request.getEstimatedValue());
        lead.setDepartCountry(request.getDepartCountry());
        lead.setDepartCity(request.getDepartCity());

        // Passenger counts
        lead.setRooms(request.getRooms());
        lead.setAdults(request.getAdults());
        lead.setChildren(request.getChildren());
        lead.setInfants(request.getInfants());
        lead.setExtraBeds(request.getExtraBeds());

        // Services — mutate the Hibernate-managed collection, never replace it
        lead.getServices().clear();
        if (request.getServices() != null) {
            lead.getServices().addAll(request.getServices());
        }

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
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("LEAD_UPDATED")
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("Lead Updated: " + updated.getCustomerName())
                .message("Lead " + updated.getCustomerName() + " was updated")
                .referenceType("LEAD")
                .referencePublicId(updated.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
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

        // Cascade: let the quotation module soft-delete this lead's quotations.
        // Published in-transaction so a synchronous listener participates in the same
        // commit (atomic cascade). The event lives in common — no cross-module import.
        eventPublisher.publishEvent(new LeadSoftDeletedEvent(lead.getId(), tenantId));
    }

    // ── Kanban board ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LeadBoardColumnDto> getLeadBoard() {
        Long tenantId = currentTenantId();

        // One query, assignee eagerly joined; grouped in memory by stage.
        Map<LeadStage, List<Lead>> byStage = leadRepository
                .findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .collect(Collectors.groupingBy(Lead::getLeadStage));

        // Emit every stage in pipeline order, including empty columns, so the
        // board always renders all seven lanes.
        List<LeadBoardColumnDto> columns = Arrays.stream(LeadStage.values())
                .map(stage -> {
                    List<Lead> stageLeads = byStage.getOrDefault(stage, List.of());
                    BigDecimal total = stageLeads.stream()
                            .map(Lead::getEstimatedValue)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return LeadBoardColumnDto.builder()
                            .stage(stage)
                            .count(stageLeads.size())
                            .totalValue(total)
                            .leads(stageLeads.stream().map(leadMapper::toResponse).toList())
                            .build();
                })
                .toList();

        // One batch quotation query for every lead across all columns (no N+1).
        enrichWithLatestQuotation(columns.stream()
                .flatMap(c -> c.getLeads().stream())
                .toList());

        return columns;
    }

    /**
     * Batch-populate {@code latestQuotation} on the given lead DTOs using a single
     * quotation query (avoids one query per lead). Mutates the DTOs in place; leads
     * with no quotation are left null.
     */
    private void enrichWithLatestQuotation(List<LeadResponseDto> dtos) {
        if (dtos.isEmpty()) return;
        List<UUID> leadIds = dtos.stream().map(LeadResponseDto::getId).toList();
        Map<UUID, QuotationRefDto> latest = quotationService.getLatestRefsByLeads(leadIds);
        if (latest.isEmpty()) return;
        dtos.forEach(dto -> dto.setLatestQuotation(latest.get(dto.getId())));
    }

    @Override
    @Transactional
    public LeadResponseDto updateLeadStage(UUID publicId, LeadStage newStage) {
        Long tenantId = currentTenantId();

        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lead not found: " + publicId));

        LeadStage oldStage = lead.getLeadStage();
        if (oldStage == newStage) {
            // No-op move (dropped back into the same column) — skip the write
            // and the notification entirely.
            return leadMapper.toResponse(lead);
        }

        lead.setLeadStage(newStage);
        Lead updated = leadRepository.save(lead);
        log.info("Lead stage changed | publicId: {} | {} -> {} | tenantId: {}",
                publicId, oldStage, newStage, tenantId);

        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("LEAD_STAGE_CHANGED")
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("Lead moved: " + updated.getCustomerName())
                .message(updated.getCustomerName() + " moved from "
                        + oldStage.getDisplayName() + " to " + newStage.getDisplayName())
                .referenceType("LEAD")
                .referencePublicId(updated.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());

        return leadMapper.toResponse(updated);
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long getLeadCountForUser(UUID userPublicId) {
        Long tenantId = currentTenantId();
        // Validates the user exists in this tenant — a foreign/unknown id
        // returns 404, not a silently wrong 0
        resolveAssignedUserForStats(userPublicId, tenantId);
        return leadRepository
                .countByAssignedUserPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserWorkloadDto> getUserWorkload() {
        return leadRepository.findUserWorkload(currentTenantId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserLeadStageCountDto> getLeadStageBreakdownPerUser() {
        return leadRepository.countLeadsByStagePerUser(currentTenantId());
    }

    /** Existence check only — unlike assignment, inactive users are fine here. */
    private void resolveAssignedUserForStats(UUID userPublicId, Long tenantId) {
        userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + userPublicId));
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
     * Resolve the assigned user's publicId to a User, scoped to the current
     * tenant so a lead can never be assigned to another tenant's user.
     * Every lead must have an owner, and that owner must be active.
     */
    private User resolveAssignedUser(UUID assignedUserId, Long tenantId) {
        if (assignedUserId == null) {
            // @NotNull on the DTO catches this first; kept as defense in depth
            throw new BusinessException(
                    "Assigned user is required", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(assignedUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assigned user not found: " + assignedUserId));

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(
                    "Cannot assign lead to inactive user: " + user.getName(),
                    HttpStatus.BAD_REQUEST);
        }
        return user;
    }

    /**
     * Get logged-in user's email for audit trail (softDelete, etc.)
     */
    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
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

        String assignedTo = lead.getAssignedUser() != null
                ? lead.getAssignedUser().getName()
                : "Unassigned";

        eventPublisher.publishEvent(
                NotifyEvent.builder()
                        .type("LEAD_CREATED")
                        .tenantId(tenantId)
                        .actorUserId(currentUserId())
                        .recipientUserIds(recipientIds)
                        .title("New Lead: " + lead.getCustomerName())
                        .message(lead.getLeadSource() + " lead from " + lead.getDepartCity()
                                + " assigned to " + assignedTo)
                        .referenceType("LEAD")
                        .referencePublicId(lead.getPublicId())
                        .channels(Set.of(DeliveryChannel.IN_APP))
                        .payload(Map.of(
                                "leadId",       lead.getPublicId().toString(),
                                "customerName", lead.getCustomerName(),
                                "source",       lead.getLeadSource(),
                                "assignedTo",   assignedTo
                        ))
                        .build()
        );

        log.info("LEAD_CREATED notification published for lead {} to {} recipients",
                lead.getPublicId(), recipientIds.size());
    }

}