package com.crm.travelcrm.accounting.tax.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * TCS under Section 206C(1G) on overseas tour packages. The annual threshold per buyer is taxed at the
 * below-threshold rate; spend beyond it at the above-threshold rate (the LRS slab). The threshold is
 * an aggregate across the financial year, so the caller supplies the buyer's prior overseas spend this
 * FY (default 0) and this splits the current package across the remaining headroom. Domestic packages
 * never attract TCS — the caller simply does not invoke this.
 *
 * <p>Rates and the threshold are externalised (per Finance Act / FY) via {@code app.accounting.tcs.*}.
 */
@Service
public class TcsCalculator {

    private final BigDecimal threshold;
    private final BigDecimal rateBelow;
    private final BigDecimal rateAbove;

    public TcsCalculator(
            @Value("${app.accounting.tcs.overseas.threshold:700000}") BigDecimal threshold,
            @Value("${app.accounting.tcs.overseas.rate-below:0.05}") BigDecimal rateBelow,
            @Value("${app.accounting.tcs.overseas.rate-above:0.20}") BigDecimal rateAbove) {
        this.threshold = threshold;
        this.rateBelow = rateBelow;
        this.rateAbove = rateAbove;
    }

    /**
     * TCS on {@code packageValue} given the buyer's prior overseas spend this FY. The portion that
     * still fits under the threshold is taxed at the below rate; the remainder at the above rate.
     */
    public BigDecimal compute(BigDecimal packageValue, BigDecimal priorOverseasSpendThisFy) {
        BigDecimal value = nz(packageValue);
        if (value.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal prior = nz(priorOverseasSpendThisFy).max(BigDecimal.ZERO);
        BigDecimal headroom = threshold.subtract(prior).max(BigDecimal.ZERO);

        BigDecimal below = value.min(headroom);
        BigDecimal above = value.subtract(below).max(BigDecimal.ZERO);

        BigDecimal tcs = below.multiply(rateBelow).add(above.multiply(rateAbove));
        return tcs.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}