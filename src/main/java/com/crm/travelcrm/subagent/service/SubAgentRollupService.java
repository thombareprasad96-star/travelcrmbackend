package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.subagent.dto.MyCommissionResponse;
import com.crm.travelcrm.subagent.dto.SubAgentCommissionLedgerDto;
import com.crm.travelcrm.subagent.dto.SubAgentRollupRow;
import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.repository.SubAgentCommissionRepository;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parent-facing B2B roll-up: aggregates each sub-agent's bookings (count, revenue, collected) and
 * accrued commission for the tenant admin. Read-only; gated on {@code USER_READ} at the controller,
 * which only the TENANT_ADMIN holds, so a sub-agent can never see the roll-up or a peer's earnings.
 */
@Service
@RequiredArgsConstructor
public class SubAgentRollupService {

    private final SubAgentProfileRepository profileRepository;
    private final SubAgentCommissionRepository commissionRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;

    /** One row per sub-agent: identity + booking aggregates + commission earned. */
    @Transactional(readOnly = true)
    public List<SubAgentRollupRow> rollup() {
        Long tenantId = requireTenant();

        Map<Long, long[]> counts = new HashMap<>();       // ownerUserId → [bookingCount]
        Map<Long, BigDecimal[]> money = new HashMap<>();  // ownerUserId → [revenue, collected]
        for (Object[] r : bookingRepository.aggregateByOwner(tenantId)) {
            Long ownerId = ((Number) r[0]).longValue();
            counts.put(ownerId, new long[]{((Number) r[1]).longValue()});
            money.put(ownerId, new BigDecimal[]{asMoney(r[2]), asMoney(r[3])});
        }

        Map<Long, BigDecimal> commission = new HashMap<>();
        for (Object[] r : commissionRepository.sumGroupedBySubAgent(tenantId)) {
            commission.put(((Number) r[0]).longValue(), asMoney(r[1]));
        }

        return profileRepository.findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(p -> {
                    User u = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId).orElse(null);
                    if (u == null) return null;
                    long[] c = counts.get(p.getUserId());
                    BigDecimal[] m = money.get(p.getUserId());
                    return SubAgentRollupRow.builder()
                            .publicId(p.getPublicId())
                            .userPublicId(u.getPublicId())
                            .name(u.getName())
                            .email(u.getEmail())
                            .status(p.getStatus())
                            .commissionType(p.getMarkupType())
                            .commissionRate(p.getMarkupValue())
                            .bookingsCount(c != null ? c[0] : 0L)
                            .totalRevenue(m != null ? m[0] : BigDecimal.ZERO)
                            .totalCollected(m != null ? m[1] : BigDecimal.ZERO)
                            .commissionEarned(commission.getOrDefault(p.getUserId(), BigDecimal.ZERO))
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** The commission ledger drill-down for one sub-agent (its accrual/reversal rows + total). */
    @Transactional(readOnly = true)
    public SubAgentCommissionLedgerDto commissionLedger(UUID profilePublicId) {
        Long tenantId = requireTenant();
        SubAgentProfile p = profileRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(profilePublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent not found: " + profilePublicId));
        User u = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent login not found"));

        List<SubAgentCommissionLedgerDto.Entry> entries = commissionRepository
                .findByTenantIdAndSubAgentUserIdAndDeletedAtIsNullOrderByOccurredOnDescIdDesc(tenantId, p.getUserId())
                .stream()
                .map(c -> SubAgentCommissionLedgerDto.Entry.builder()
                        .bookingCode(c.getBookingCode())
                        .amount(c.getAmount())
                        .note(c.getNote())
                        .occurredOn(c.getOccurredOn())
                        .createdAt(c.getCreatedAt())
                        .build())
                .toList();

        return SubAgentCommissionLedgerDto.builder()
                .subAgentPublicId(p.getPublicId())
                .name(u.getName())
                .totalEarned(commissionRepository.sumByTenantAndSubAgent(tenantId, p.getUserId()))
                .entries(entries)
                .build();
    }

    /**
     * The CURRENT sub-agent's own commission (self-service): their rate + full ledger. Resolved
     * strictly from the caller's own user id (never a path param), so a sub-agent can only ever see
     * themselves. 404 if the caller isn't an active-or-suspended sub-agent in this tenant.
     */
    @Transactional(readOnly = true)
    public MyCommissionResponse myCommission(Long userId) {
        Long tenantId = requireTenant();
        SubAgentProfile p = profileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .filter(sp -> tenantId.equals(sp.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("You are not a sub-agent."));
        User u = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent login not found"));

        List<SubAgentCommissionLedgerDto.Entry> entries = commissionRepository
                .findByTenantIdAndSubAgentUserIdAndDeletedAtIsNullOrderByOccurredOnDescIdDesc(tenantId, userId)
                .stream()
                .map(c -> SubAgentCommissionLedgerDto.Entry.builder()
                        .bookingCode(c.getBookingCode())
                        .amount(c.getAmount())
                        .note(c.getNote())
                        .occurredOn(c.getOccurredOn())
                        .createdAt(c.getCreatedAt())
                        .build())
                .toList();

        return MyCommissionResponse.builder()
                .name(u.getName())
                .commissionType(p.getMarkupType())
                .commissionRate(p.getMarkupValue())
                .totalEarned(commissionRepository.sumByTenantAndSubAgent(tenantId, userId))
                .entries(entries)
                .build();
    }

    private static BigDecimal asMoney(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }

    private Long requireTenant() {
        Long t = TenantContext.getTenantId();
        if (t == null) {
            throw new IllegalStateException("TenantContext is empty — JwtAuthFilter must run.");
        }
        return t;
    }
}
