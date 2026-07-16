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

    /**
     * Effective per-seat ONE-TIME license (unlock) rate for a tenant. Resolution: the tenant's
     * {@code subAgentSeatLicenseFee} override → the platform default
     * ({@link PlatformConfigService#SUBAGENT_SEAT_LICENSE_FEE}) → and, when that is unset (blank), it
     * falls back to the recurring seat rate so a tenant is never charged 0 to unlock unless a SuperAdmin
     * explicitly configured it. Never negative.
     */
    public BigDecimal effectiveLicenseRate(Tenant tenant) {
        if (tenant != null && tenant.getSubAgentSeatLicenseFee() != null) {
            return tenant.getSubAgentSeatLicenseFee().max(BigDecimal.ZERO);
        }
        String raw = configService.get(PlatformConfigService.SUBAGENT_SEAT_LICENSE_FEE, "");
        if (raw == null || raw.isBlank()) {
            return effectiveRate(tenant);   // unset → mirror the recurring seat rate
        }
        // A non-numeric typo (e.g. "1,000", "₹500") must NOT silently price the unlock at 0 — fall back
        // to the recurring rate. Only an explicitly-parseable value (incl. a deliberate "0") is honoured.
        BigDecimal parsed = parseRateOrNull(raw);
        return parsed != null ? parsed : effectiveRate(tenant);
    }

    /** The one-time charge to license {@code quantity} seats: quantity × effective license rate. */
    public LicenseFeeQuote licenseFee(Tenant tenant, int quantity) {
        int qty = Math.max(1, quantity);
        BigDecimal unit = effectiveLicenseRate(tenant);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
        return new LicenseFeeQuote(qty, unit, total);
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

    /** Parse a configured rate; {@code null} on a non-numeric value so callers can choose a fallback
     *  (a negative clamps to 0, an explicit "0" is honoured). */
    private static BigDecimal parseRateOrNull(String raw) {
        if (raw == null) return null;
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() < 0 ? BigDecimal.ZERO : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Active-seat count, the resolved per-seat rate, and their product (all for one tenant/period). */
    public record SeatFeeQuote(long activeSeats, BigDecimal rate, BigDecimal total) {}

    /** A one-time seat-license charge: the seat quantity, the per-seat unlock rate, and their product. */
    public record LicenseFeeQuote(int quantity, BigDecimal unitRate, BigDecimal total) {}
}
