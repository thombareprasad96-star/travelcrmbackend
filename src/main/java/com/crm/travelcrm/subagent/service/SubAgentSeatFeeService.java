package com.crm.travelcrm.subagent.service;

import com.crm.travelcrm.platform.config.service.PlatformConfigService;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the monthly B2B sub-agent seat fee to bill a tenant: {@code active sub-agents × rate}.
 * The rate is the tenant's per-tenant override ({@code Tenant.subAgentSeatFee}) when set, else the
 * platform-flat default ({@link PlatformConfigService#SUBAGENT_SEAT_FEE}). Only ACTIVE sub-agents are
 * billed (a suspended one holds a provisioned slot but isn't operating).
 */
@Service
@RequiredArgsConstructor
public class SubAgentSeatFeeService {

    private final SubAgentProfileRepository profileRepository;
    private final PlatformConfigService configService;

    /** Effective per-seat monthly rate for a tenant (override → platform default → 0). Never negative. */
    public BigDecimal effectiveRate(Tenant tenant) {
        if (tenant != null && tenant.getSubAgentSeatFee() != null) {
            return tenant.getSubAgentSeatFee().max(BigDecimal.ZERO);
        }
        return parseRate(configService.get(PlatformConfigService.SUBAGENT_SEAT_FEE, "0"));
    }

    /** The seat fee this tenant owes for the period: active-seat count × effective rate. */
    @Transactional(readOnly = true)
    public SeatFeeQuote quote(Tenant tenant) {
        long seats = profileRepository.countByTenantIdAndStatusAndDeletedAtIsNull(
                tenant.getId(), SubAgentStatus.ACTIVE);
        BigDecimal rate = effectiveRate(tenant);
        BigDecimal total = rate.multiply(BigDecimal.valueOf(seats)).setScale(2, RoundingMode.HALF_UP);
        return new SeatFeeQuote(seats, rate, total);
    }

    private static BigDecimal parseRate(String raw) {
        if (raw == null) return BigDecimal.ZERO;
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() < 0 ? BigDecimal.ZERO : v;
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    /** Active-seat count, the resolved per-seat rate, and their product (all for one tenant/period). */
    public record SeatFeeQuote(long activeSeats, BigDecimal rate, BigDecimal total) {}
}
