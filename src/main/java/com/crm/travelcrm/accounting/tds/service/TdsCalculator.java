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
 * Computes TDS to withhold when paying a vendor. The section rate is externalised per FY
 * ({@code app.accounting.tds.*}); under Section 206AA, when the vendor has furnished no PAN the higher
 * of the section rate and the flat no-PAN rate (20%) applies. TDS timing follows the statutory
 * "earlier of credit or payment" — here it is booked when the bill is raised.
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

    public TdsResult compute(BigDecimal base, TdsSection section, boolean hasPan) {
        if (section == null || base == null || base.signum() <= 0) {
            return TdsResult.none();
        }
        BigDecimal sectionRate = rates.getOrDefault(section, BigDecimal.ZERO);
        BigDecimal rate = hasPan ? sectionRate : sectionRate.max(noPanRate);   // 206AA: higher of
        BigDecimal amount = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal ratePct = rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        return new TdsResult(ratePct, amount);
    }
}