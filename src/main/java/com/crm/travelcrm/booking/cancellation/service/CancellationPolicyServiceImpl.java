package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyResponse;
import com.crm.travelcrm.booking.cancellation.dto.CreateCancellationPolicyRequest;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import com.crm.travelcrm.booking.cancellation.mapper.CancellationPolicyMapper;
import com.crm.travelcrm.booking.cancellation.repository.CancellationPolicyRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CancellationPolicyServiceImpl implements CancellationPolicyService {

    private static final Logger log = LogManager.getLogger(CancellationPolicyServiceImpl.class);

    private final CancellationPolicyRepository policyRepository;
    private final CancellationPolicyMapper mapper;
    private final CancellationPolicyValidator validator;
    private final BookingRepository bookingRepository;

    @Override
    @Transactional
    public CancellationPolicyResponse save(CreateCancellationPolicyRequest request) {
        Long tenantId = requireTenantId();

        // Owner consistency: PACKAGE needs an owner; COMPANY must not have one.
        if (request.getLevel() == CancellationPolicyLevel.PACKAGE && request.getOwnerPublicId() == null) {
            throw new BusinessException("A package policy requires an ownerPublicId.", HttpStatus.BAD_REQUEST);
        }
        if (request.getLevel() == CancellationPolicyLevel.COMPANY && request.getOwnerPublicId() != null) {
            throw new BusinessException("The company default policy must not have an ownerPublicId.",
                    HttpStatus.BAD_REQUEST);
        }
        validator.validate(request.getBands());

        // A future effectiveFrom is refused, because version resolution is date-ordered and does NOT
        // filter on `active` — deliberately, since a booking must be able to resolve the version that
        // governed it even after that version was superseded. Combine the two and a post-dated version
        // outlives its own retirement: publish v2 for 1 Jan next year, publish v3 today (which
        // deactivates v2), and from 1 Jan `companyDefaultAsOf` starts returning the RETIRED v2,
        // because its date sorts highest. Every booking pinned or cancelled from then on is charged
        // under a policy the tenant already withdrew — on ₹1,00,000 cancelled 40 days out that was a
        // ₹52,500 refund where the live policy says ₹94,500.
        //
        // Scheduling a policy ahead of time is a legitimate thing to want; it needs an effectiveTo on
        // the superseded row so retirement can win over recency. Until that exists, refusing is the
        // honest answer — the alternative is silently mispricing cancellations months later.
        if (request.getEffectiveFrom() != null && request.getEffectiveFrom().isAfter(LocalDate.now())) {
            throw new BusinessException(
                    "A cancellation policy cannot be dated in the future (" + request.getEffectiveFrom()
                            + "). Publish it on the day it takes effect.",
                    HttpStatus.BAD_REQUEST);
        }

        // Derive the next version and deactivate the prior active one (never mutated in place).
        List<CancellationPolicy> history = historyRows(tenantId, request.getLevel(), request.getOwnerPublicId());
        int nextVersion = history.isEmpty() ? 1 : history.get(0).getVersion() + 1;
        history.stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .forEach(p -> { p.setActive(false); policyRepository.save(p); });

        CancellationPolicy policy = CancellationPolicy.builder()
                .name(defaultName(request))
                .level(request.getLevel())
                .ownerPublicId(request.getOwnerPublicId())
                .version(nextVersion)
                .active(true)
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : LocalDate.now())
                .gstOnChargeApplicable(request.getGstOnChargeApplicable() == null || request.getGstOnChargeApplicable())
                .tcsRefundable(request.getTcsRefundable() == null || request.getTcsRefundable())
                .build();
        policy.setTenantId(tenantId);
        policy.getBands().addAll(mapper.toBands(request.getBands()));

        CancellationPolicy saved = policyRepository.save(policy);
        log.info("Saved cancellation policy {} v{} ({}) for tenant {}",
                saved.getPublicId(), saved.getVersion(), saved.getLevel(), tenantId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CancellationPolicyResponse> listActive() {
        Long tenantId = requireTenantId();
        return policyRepository
                .findByTenantIdAndActiveTrueAndDeletedAtIsNullOrderByLevelAscCreatedAtDesc(tenantId)
                .stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CancellationPolicyResponse getByPublicId(UUID publicId) {
        Long tenantId = requireTenantId();
        return mapper.toResponse(loadOrThrow(publicId, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CancellationPolicyResponse> history(CancellationPolicyLevel level, UUID ownerPublicId) {
        Long tenantId = requireTenantId();
        return historyRows(tenantId, level, ownerPublicId).stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = requireTenantId();
        CancellationPolicy policy = loadOrThrow(publicId, tenantId);

        // A version a live booking pins must never be physically removed — its cancellation math
        // must stay reproducible. Retiring it here soft-deletes; block it while any non-cancelled
        // booking still references this exact version.
        long pinned = bookingRepository.countByCancellationPolicyPublicIdAndDeletedAtIsNull(publicId);
        if (pinned > 0) {
            throw new BusinessException(
                    "This policy version is in use by " + pinned + " booking(s) and cannot be deleted. "
                            + "Publish a new version instead.", HttpStatus.CONFLICT);
        }
        policy.setActive(false);
        policy.softDelete(currentUserEmail());
        policyRepository.save(policy);
        log.info("Retired cancellation policy {} (tenant {})", publicId, tenantId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<CancellationPolicy> historyRows(Long tenantId, CancellationPolicyLevel level, UUID ownerPublicId) {
        return ownerPublicId == null
                ? policyRepository
                    .findByTenantIdAndLevelAndOwnerPublicIdIsNullAndDeletedAtIsNullOrderByVersionDesc(tenantId, level)
                : policyRepository
                    .findByTenantIdAndLevelAndOwnerPublicIdAndDeletedAtIsNullOrderByVersionDesc(tenantId, level, ownerPublicId);
    }

    private CancellationPolicy loadOrThrow(UUID publicId, Long tenantId) {
        return policyRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation policy not found: " + publicId));
    }

    private static String defaultName(CreateCancellationPolicyRequest request) {
        if (request.getName() != null && !request.getName().isBlank()) {
            return request.getName();
        }
        return request.getLevel() == CancellationPolicyLevel.COMPANY ? "Company default" : "Package policy";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}