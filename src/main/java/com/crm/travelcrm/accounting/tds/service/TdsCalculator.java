package com.crm.travelcrm.accounting.tds.service;

import com.crm.travelcrm.accounting.tds.dto.TdsResult;
import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Computes TDS to withhold when paying a vendor. <b>Section rates are per-TENANT</b>, supplied by the
 * caller from {@code AccountingSettings}; the {@code app.accounting.tds.*} properties survive only as
 * the platform defaults that seed a tenant's first settings row and as the fallback when none exists.
 *
 * <p>Under Section 206AA, when the vendor has furnished no PAN the higher of the section rate and the
 * no-PAN rate applies — that is a statutory rule rather than a preference, so it is enforced here
 * whatever the tenant configured. TDS timing follows the statutory "earlier of credit or payment" —
 * here it is booked when the bill is raised.
 */
@Service
public class TdsCalculator {

    private final Map<TdsSection, BigDecimal> rates = new EnumMap<>(TdsSection.class);
    private final BigDecimal noPanRate;

    public TdsCalculator(
            @Value("${app.accounting.tds.section-194c:0.02}") BigDecimal r194c,
            @Value("${app.accounting.tds.section-194h:0.05}") BigDecimal r194h,
            @Value("${app.accounting.tds.section-194j:0.10}") BigDecimal r194j,
            @Value("${app.accounting.tds.no-pan-rate:0.20}") BigDecimal noPanRate) {
        rates.put(TdsSection.SEC_194C, r194c);
        rates.put(TdsSection.SEC_194H, r194h);
        rates.put(TdsSection.SEC_194J, r194j);
        this.noPanRate = noPanRate;
    }

    /**
     * The section rates a tenant withholds at. FRACTIONS here (0.02 = 2%) — the conversion from the
     * percent the tenant edits happens once, at the call site. Any null member falls back to the
     * platform default, so a partially-populated policy is still safe.
     */
    public record TdsRates(BigDecimal sec194c, BigDecimal sec194h, BigDecimal sec194j, BigDecimal noPan) {}

    /** The platform defaults, used when a tenant has no settings row yet. */
    public TdsRates defaults() {
        return new TdsRates(rates.get(TdsSection.SEC_194C), rates.get(TdsSection.SEC_194H),
                rates.get(TdsSection.SEC_194J), noPanRate);
    }

    /** Compute under the PLATFORM defaults. Prefer the four-arg overload — rates belong to the tenant. */
    public TdsResult compute(BigDecimal base, TdsSection section, boolean hasPan) {
        return compute(base, section, hasPan, defaults());
    }

    /** Compute under the TENANT's rates. 206AA (no PAN ⇒ the higher rate) is a statutory rule, not
     *  a setting, so it is applied here regardless of what the tenant configured. */
    public TdsResult compute(BigDecimal base, TdsSection section, boolean hasPan, TdsRates tenantRates) {
        if (section == null || base == null || base.signum() <= 0) {
            return TdsResult.none();
        }
        TdsRates r = tenantRates != null ? tenantRates : defaults();
        BigDecimal sectionRate = switch (section) {
            case SEC_194C -> firstNonNull(r.sec194c(), rates.get(TdsSection.SEC_194C));
            case SEC_194H -> firstNonNull(r.sec194h(), rates.get(TdsSection.SEC_194H));
            case SEC_194J -> firstNonNull(r.sec194j(), rates.get(TdsSection.SEC_194J));
        };
        BigDecimal noPan = firstNonNull(r.noPan(), noPanRate);

        BigDecimal rate = hasPan ? sectionRate : sectionRate.max(noPan);   // 206AA: higher of
        BigDecimal amount = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ratePct = rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return new TdsResult(ratePct, amount);
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b) {
        if (a != null) return a;
        return b != null ? b : BigDecimal.ZERO;
    }
}