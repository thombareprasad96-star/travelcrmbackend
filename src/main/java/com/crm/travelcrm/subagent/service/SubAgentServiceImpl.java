package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.auth.util.UsernamePolicy;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.subagent.dto.CreateSubAgentRequest;
import com.crm.travelcrm.subagent.dto.CreateSubAgentResponse;
import com.crm.travelcrm.subagent.dto.SubAgentResponse;
import com.crm.travelcrm.subagent.dto.UpdateSubAgentRequest;
import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import com.crm.travelcrm.subagent.license.service.SubAgentLicenseService;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentServiceImpl implements SubAgentService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SubAgentProfileRepository profileRepository;
    private final TenantRepository tenantRepository;
    private final SubAgentLicenseService licenseService;

    @Override
    @Transactional
    public CreateSubAgentResponse create(CreateSubAgentRequest req) {
        Long tenantId = requireTenant();
        String email = req.getEmail().trim().toLowerCase();
        String username = UsernamePolicy.normalize(req.getUsername());

        // Username is unique platform-wide (uq_users_username_active) — a partner already onboarded
        // by another agency cannot reuse that login and needs a distinct one. Email is NOT checked:
        // it is a contact field now, and a partner may legitimately share one with their own staff.
        if (userRepository.existsByUsernameAndDeletedAtIsNull(username)) {
            throw new BusinessException(
                    "The username " + username + " is already taken.",
                    HttpStatus.CONFLICT);
        }

        MarkupType markupType = req.getMarkupType() != null ? req.getMarkupType() : MarkupType.PERCENT;
        BigDecimal markupValue = req.getMarkupValue() != null ? req.getMarkupValue() : BigDecimal.ZERO;
        validateMarkup(markupType, markupValue);

        // Check available licensed seats FIRST. A licensed seat is any non-PENDING_LICENSE profile under
        // the tenant cap. If one is free → provision ACTIVE (classic path). If not → provision
        // PENDING_LICENSE (login disabled) and open a seat-license purchase the tenant must pay + a
        // SuperAdmin must approve before it activates.
        boolean seatAvailable = seatAvailable(tenantId);

        // 1) The login User (role SUB_AGENT). tenantId set explicitly (User extends BaseEntity, so it
        //    is NOT auto-stamped). No CREATABLE_ROLES whitelist here on purpose — SUB_AGENT is minted
        //    only through this dedicated flow, never the generic /api/users endpoint. A PENDING_LICENSE
        //    sub-agent's login stays disabled (isActive=false) until the seat is approved.
        User user = User.builder()
                .name(req.getName().trim())
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.SUB_AGENT)
                .tenantId(tenantId)
                .phoneNumber(req.getPhoneNumber())
                .isActive(seatAvailable)
                .build();
        User savedUser = userRepository.save(user);

        // 2) The paired profile (tenantId auto-stamped).
        SubAgentProfile profile = SubAgentProfile.builder()
                .userId(savedUser.getId())
                .markupType(markupType)
                .markupValue(markupValue)
                .status(seatAvailable ? SubAgentStatus.ACTIVE : SubAgentStatus.PENDING_LICENSE)
                .brandName(req.getBrandName())
                .logoUrl(req.getLogoUrl())
                .contactPhone(req.getContactPhone())
                .contactEmail(req.getContactEmail())
                .brandColor(req.getBrandColor())
                .build();
        SubAgentProfile saved = profileRepository.save(profile);

        if (seatAvailable) {
            log.info("Sub-agent provisioned ACTIVE | username={} tenantId={} markup={} {}",
                    username, tenantId, markupType, markupValue);
            return CreateSubAgentResponse.builder()
                    .subAgent(toResponse(saved, savedUser))
                    .licenseRequired(false)
                    .build();
        }

        // Over cap — open the seat-license purchase for this pending sub-agent (issues the invoice).
        log.info("Sub-agent provisioned PENDING_LICENSE (over cap) | username={} tenantId={}", username, tenantId);
        SubAgentLicenseRequestResponse licenseRequest = licenseService.openForPending(
                tenantId, currentUserEmail(), saved.getPublicId(),
                req.getPaymentMode(), req.getOfflineMode(), req.getOfflineReference(), req.getOfflineNotes());
        return CreateSubAgentResponse.builder()
                .subAgent(toResponse(saved, savedUser))
                .licenseRequired(true)
                .licenseRequest(licenseRequest)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubAgentResponse> list() {
        Long tenantId = requireTenant();
        return profileRepository.findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(p -> userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId)
                        .map(u -> toResponse(p, u))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SubAgentResponse get(UUID publicId) {
        Long tenantId = requireTenant();
        SubAgentProfile p = loadOwned(publicId, tenantId);
        return toResponse(p, requireUser(p.getUserId(), tenantId));
    }

    @Override
    @Transactional
    public SubAgentResponse update(UUID publicId, UpdateSubAgentRequest req) {
        Long tenantId = requireTenant();
        SubAgentProfile p = loadOwned(publicId, tenantId);
        User u = requireUser(p.getUserId(), tenantId);

        // PENDING_LICENSE is driven only by the seat-purchase flow, never the generic update:
        //  - you cannot manually move a sub-agent INTO PENDING_LICENSE, and
        //  - you cannot activate/suspend one that is still awaiting seat approval (that would grant a
        //    login the tenant hasn't paid for). Branding/markup/name edits stay allowed while pending.
        if (req.getStatus() == SubAgentStatus.PENDING_LICENSE) {
            throw new BusinessException("Licensing status is managed by the seat-purchase flow.",
                    HttpStatus.BAD_REQUEST);
        }
        if (p.getStatus() == SubAgentStatus.PENDING_LICENSE
                && (req.getStatus() != null || req.getActive() != null)) {
            throw new BusinessException(
                    "This sub-agent is awaiting seat-license approval and cannot be activated or suspended yet.",
                    HttpStatus.CONFLICT);
        }

        if (req.getMarkupType() != null)  p.setMarkupType(req.getMarkupType());
        if (req.getMarkupValue() != null) p.setMarkupValue(req.getMarkupValue());
        validateMarkup(p.getMarkupType(), p.getMarkupValue());

        if (req.getStatus() != null)      p.setStatus(req.getStatus());
        if (req.getBrandName() != null)   p.setBrandName(req.getBrandName());
        if (req.getLogoUrl() != null)     p.setLogoUrl(req.getLogoUrl());
        if (req.getContactPhone() != null) p.setContactPhone(req.getContactPhone());
        if (req.getContactEmail() != null) p.setContactEmail(req.getContactEmail());
        if (req.getBrandColor() != null)  p.setBrandColor(req.getBrandColor());

        if (req.getName() != null)        u.setName(req.getName().trim());
        if (req.getPhoneNumber() != null) u.setPhoneNumber(req.getPhoneNumber());
        // Keep the login active-flag in step with suspension; an explicit toggle wins.
        if (req.getStatus() != null)      u.setIsActive(req.getStatus() == SubAgentStatus.ACTIVE);
        if (req.getActive() != null)      u.setIsActive(req.getActive());

        userRepository.save(u);
        SubAgentProfile saved = profileRepository.save(p);
        return toResponse(saved, u);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = requireTenant();
        SubAgentProfile p = loadOwned(publicId, tenantId);
        String actor = currentUserEmail();

        p.softDelete(actor);
        profileRepository.save(p);

        userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId).ifPresent(u -> {
            u.setIsActive(false);       // kill the login immediately
            u.softDelete(actor);
            userRepository.save(u);
        });

        // If this sub-agent was still awaiting a seat license, void its unpaid invoice + cancel the request.
        licenseService.onSubAgentDeleted(tenantId, p.getId());
        log.info("Sub-agent removed | profilePublicId={} tenantId={}", publicId, tenantId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SubAgentProfile loadOwned(UUID publicId, Long tenantId) {
        return profileRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent not found: " + publicId));
    }

    private User requireUser(Long userId, Long tenantId) {
        return userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent login not found"));
    }

    /**
     * Is a licensed seat free for a new sub-agent? The cap ({@code Tenant.maxSubAgents} = plan default +
     * purchased add-on seats; null/0 = none) is compared against the LICENSED-consuming count — every
     * profile except {@code PENDING_LICENSE}, so a not-yet-paid sub-agent never counts against the cap.
     * When false, the caller provisions PENDING_LICENSE and routes the tenant to purchase a seat.
     * {@code Tenant} is platform-level (no tenant filter), so a direct {@code findById} is correct here.
     */
    private boolean seatAvailable(Long tenantId) {
        // Pessimistic-write lock the tenant row so two concurrent create() calls can't both pass this
        // check-then-act seat check and over-provision beyond the paid cap. Held until create() commits.
        Tenant tenant = tenantRepository.findByIdForUpdate(tenantId).orElse(null);
        int cap = (tenant == null || tenant.getMaxSubAgents() == null) ? 0 : Math.max(0, tenant.getMaxSubAgents());
        long licensedUsed = profileRepository
                .countByTenantIdAndStatusNotAndDeletedAtIsNull(tenantId, SubAgentStatus.PENDING_LICENSE);
        return licensedUsed < cap;
    }

    private void validateMarkup(MarkupType type, BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new BusinessException("Markup value must be zero or positive.", HttpStatus.BAD_REQUEST);
        }
        if (type == MarkupType.PERCENT && value.compareTo(HUNDRED) > 0) {
            throw new BusinessException("Percentage markup cannot exceed 100.", HttpStatus.BAD_REQUEST);
        }
    }

    private Long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) {
            throw new IllegalStateException("TenantContext is empty — JwtAuthFilter must run.");
        }
        return t;
    }

    /**
     * The acting staff member's real mailbox — deliberately NOT {@code auth.getName()}, which now
     * returns the username. This feeds {@code SubAgentLicenseRequest.requestedByEmail}, which a
     * SuperAdmin OUTSIDE this tenant reads to contact whoever asked for the seat; a username is not
     * something they can act on.
     */
    private String currentUserEmail() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof User u) ? u.getEmail() : "system";
    }

    private SubAgentResponse toResponse(SubAgentProfile p, User u) {
        return SubAgentResponse.builder()
                .publicId(p.getPublicId())
                .userPublicId(u.getPublicId())
                .name(u.getName())
                .username(u.getUsername())
                .email(u.getEmail())
                .phoneNumber(u.getPhoneNumber())
                .markupType(p.getMarkupType())
                .markupValue(p.getMarkupValue())
                .status(p.getStatus())
                .active(Boolean.TRUE.equals(u.getIsActive()))
                .brandName(p.getBrandName())
                .logoUrl(p.getLogoUrl())
                .contactPhone(p.getContactPhone())
                .contactEmail(p.getContactEmail())
                .brandColor(p.getBrandColor())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
