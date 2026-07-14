package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.subagent.dto.CreateSubAgentRequest;
import com.crm.travelcrm.subagent.dto.SubAgentResponse;
import com.crm.travelcrm.subagent.dto.UpdateSubAgentRequest;
import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
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

    @Override
    @Transactional
    public SubAgentResponse create(CreateSubAgentRequest req) {
        Long tenantId = requireTenant();
        String email = req.getEmail().trim().toLowerCase();

        // Email is unique per tenant (uq_user_email_tenant).
        if (userRepository.existsByEmailAndTenantId(email, tenantId)) {
            throw new BusinessException(
                    "A user with email " + email + " already exists in your organization.",
                    HttpStatus.CONFLICT);
        }

        MarkupType markupType = req.getMarkupType() != null ? req.getMarkupType() : MarkupType.PERCENT;
        BigDecimal markupValue = req.getMarkupValue() != null ? req.getMarkupValue() : BigDecimal.ZERO;
        validateMarkup(markupType, markupValue);

        // 1) The login User (role SUB_AGENT). tenantId set explicitly (User extends BaseEntity, so it
        //    is NOT auto-stamped). No CREATABLE_ROLES whitelist here on purpose — SUB_AGENT is minted
        //    only through this dedicated flow, never the generic /api/users endpoint.
        User user = User.builder()
                .name(req.getName().trim())
                .email(email)
                .password(passwordEncoder.encode(req.getPassword()))
                .role(Role.SUB_AGENT)
                .tenantId(tenantId)
                .phoneNumber(req.getPhoneNumber())
                .isActive(true)
                .build();
        User savedUser = userRepository.save(user);

        // 2) The paired profile (tenantId auto-stamped).
        SubAgentProfile profile = SubAgentProfile.builder()
                .userId(savedUser.getId())
                .markupType(markupType)
                .markupValue(markupValue)
                .status(SubAgentStatus.ACTIVE)
                .brandName(req.getBrandName())
                .logoUrl(req.getLogoUrl())
                .contactPhone(req.getContactPhone())
                .contactEmail(req.getContactEmail())
                .brandColor(req.getBrandColor())
                .build();
        SubAgentProfile saved = profileRepository.save(profile);

        log.info("Sub-agent provisioned | email={} tenantId={} markup={} {}",
                email, tenantId, markupType, markupValue);
        return toResponse(saved, savedUser);
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

    private String currentUserEmail() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }

    private SubAgentResponse toResponse(SubAgentProfile p, User u) {
        return SubAgentResponse.builder()
                .publicId(p.getPublicId())
                .userPublicId(u.getPublicId())
                .name(u.getName())
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
