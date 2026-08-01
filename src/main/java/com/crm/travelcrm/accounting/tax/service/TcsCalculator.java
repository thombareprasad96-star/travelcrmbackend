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
 * <p><b>The slab is per-TENANT</b>, supplied by the caller from {@code AccountingSettings}. The
 * {@code app.accounting.tcs.*} properties survive only as the platform default used to seed a
 * tenant's first settings row and as the fallback when none exists. Statutory rates move with every
 * Finance Act and tenants adopt the change on their CA's advice, not on this platform's deploy
 * schedule — so the rate is theirs to set.
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
     * The slab a tenant collects TCS under. Rates are FRACTIONS here (0.05 = 5%) — the conversion
     * from the percent the tenant edits happens once, at the call site.
     *
     * <p>A flat regime (which is what the law became on 1 Apr 2026: 2%, no threshold) is expressed
     * by setting {@code rateBelow == rateAbove}; the threshold then makes no difference.
     */
    public record TcsSlab(BigDecimal threshold, BigDecimal rateBelow, BigDecimal rateAbove) {}

    /** The platform defaults, used when a tenant has no settings row yet. */
    public TcsSlab defaults() {
        return new TcsSlab(threshold, rateBelow, rateAbove);
    }

    /**
     * TCS on {@code packageValue} given the buyer's prior overseas spend this FY, under the
     * PLATFORM default slab. Prefer the three-arg overload — the slab belongs to the tenant.
     */
    public BigDecimal compute(BigDecimal packageValue, BigDecimal priorOverseasSpendThisFy) {
        return compute(packageValue, priorOverseasSpendThisFy, defaults());
    }

    /**
     * TCS on {@code packageValue} under the TENANT's slab. The portion that still fits under the
     * threshold is taxed at the below rate; the remainder at the above rate.
     */
    public BigDecimal compute(BigDecimal packageValue, BigDecimal priorOverseasSpendThisFy, TcsSlab slab) {
        BigDecimal value = nz(packageValue);
        if (value.signum() <= 0) return BigDecimal.ZERO;

        TcsSlab s = slab != null ? slab : defaults();
        BigDecimal prior = nz(priorOverseasSpendThisFy).max(BigDecimal.ZERO);
        BigDecimal headroom = nz(s.threshold()).subtract(prior).max(BigDecimal.ZERO);

        BigDecimal below = value.min(headroom);
        BigDecimal above = value.subtract(below).max(BigDecimal.ZERO);

        BigDecimal tcs = below.multiply(nz(s.rateBelow())).add(above.multiply(nz(s.rateAbove())));
        return tcs.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}