package com.crm.travelcrm.accounting.settings.service;

import com.crm.travelcrm.accounting.settings.dto.AccountingSettingsDto;
import com.crm.travelcrm.accounting.settings.dto.UpdateAccountingSettingsRequest;
import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import com.crm.travelcrm.accounting.settings.repository.AccountingSettingsRepository;
import com.crm.travelcrm.accounting.support.GstStateCodes;
import com.crm.travelcrm.accounting.tax.service.HsnSacRateService;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Loads and updates the tenant's {@link AccountingSettings} — exactly one row per tenant, lazily
 * created on first access (mirroring {@code CompanyService.loadOrCreate}). On first creation the GST
 * scheme is defaulted from whether the {@code Company} profile carries a GSTIN (registered ⇒ REGULAR,
 * otherwise UNREGISTERED) and the tenant's HSN/SAC rate defaults are seeded.
 */
@Service
@RequiredArgsConstructor
public class AccountingSettingsService {

    private final AccountingSettingsRepository repository;
    private final CompanyRepository companyRepository;
    private final HsnSacRateService hsnSacRateService;

    /**
     * The pre-per-tenant platform defaults, kept ONLY to seed a tenant's first settings row. Nothing
     * reads them once the row exists — booking tax is the tenant's, not the platform's.
     */
    @Value("${app.booking.gst-rate:0.05}")
    private BigDecimal legacyGstRate;

    @Value("${app.booking.tcs-rate:0.05}")
    private BigDecimal legacyTcsRate;

    @Value("${app.accounting.tcs.overseas.threshold:700000}")
    private BigDecimal legacyTcsThreshold;

    @Value("${app.accounting.tcs.overseas.rate-below:0.05}")
    private BigDecimal legacyTcsRateBelow;

    @Value("${app.accounting.tcs.overseas.rate-above:0.20}")
    private BigDecimal legacyTcsRateAbove;

    @Value("${app.accounting.tds.section-194c:0.02}")
    private BigDecimal legacyTds194c;

    @Value("${app.accounting.tds.section-194h:0.05}")
    private BigDecimal legacyTds194h;

    @Value("${app.accounting.tds.section-194j:0.10}")
    private BigDecimal legacyTds194j;

    @Value("${app.accounting.tds.no-pan-rate:0.20}")
    private BigDecimal legacyTdsNoPan;

    @Transactional
    public AccountingSettingsDto get(Long tenantId) {
        return toDto(loadOrCreate(tenantId), company(tenantId));
    }

    @Transactional
    public AccountingSettingsDto update(UpdateAccountingSettingsRequest req, Long tenantId) {
        AccountingSettings s = loadOrCreate(tenantId);
        if (req.getGstScheme() != null)             s.setGstScheme(req.getGstScheme());
        if (req.getAutoTcsOnOverseas() != null)     s.setAutoTcsOnOverseas(req.getAutoTcsOnOverseas());
        if (req.getRoundInvoiceTotal() != null)     s.setRoundInvoiceTotal(req.getRoundInvoiceTotal());
        if (req.getInputTaxCreditEligible() != null) s.setInputTaxCreditEligible(req.getInputTaxCreditEligible());
        // Booking-level tax — the figures the customer is actually charged.
        if (req.getApplyGstOnBookings() != null)    s.setApplyGstOnBookings(req.getApplyGstOnBookings());
        if (req.getBookingGstRatePct() != null)     s.setBookingGstRatePct(req.getBookingGstRatePct());
        if (req.getTcsApplicability() != null)      s.setTcsApplicability(req.getTcsApplicability());
        if (req.getBookingTcsRatePct() != null)     s.setBookingTcsRatePct(req.getBookingTcsRatePct());
        // Statutory TCS slab (invoice path)
        if (req.getTcsThreshold() != null)          s.setTcsThreshold(req.getTcsThreshold());
        if (req.getTcsRateBelowPct() != null)       s.setTcsRateBelowPct(req.getTcsRateBelowPct());
        if (req.getTcsRateAbovePct() != null)       s.setTcsRateAbovePct(req.getTcsRateAbovePct());
        // TDS withheld on vendor bills
        if (req.getTds194cPct() != null)            s.setTds194cPct(req.getTds194cPct());
        if (req.getTds194hPct() != null)            s.setTds194hPct(req.getTds194hPct());
        if (req.getTds194jPct() != null)            s.setTds194jPct(req.getTds194jPct());
        if (req.getTdsNoPanPct() != null)           s.setTdsNoPanPct(req.getTdsNoPanPct());
        return toDto(repository.save(s), company(tenantId));
    }

    /** Load-or-create the settings row; also ensures the HSN/SAC defaults exist for the tenant. */
    @Transactional
    public AccountingSettings loadOrCreate(Long tenantId) {
        AccountingSettings s = repository.findByTenantId(tenantId).orElse(null);
        if (s == null) {
            Company co = company(tenantId);
            GstScheme scheme = (co != null && StringUtils.hasText(co.getGstin()))
                    ? GstScheme.REGULAR : GstScheme.UNREGISTERED;
            // Seed the booking tax rates from the legacy platform-wide properties so an environment
            // that had tuned app.booking.gst-rate / tcs-rate keeps exactly the behaviour it had
            // before these became per-tenant. The properties are FRACTIONS (0.05); the columns are
            // PERCENTS (5.00), because that is what the tenant sees and edits.
            s = repository.save(AccountingSettings.builder()
                    .gstScheme(scheme)
                    .bookingGstRatePct(asPercent(legacyGstRate))
                    .bookingTcsRatePct(asPercent(legacyTcsRate))
                    .tcsThreshold(legacyTcsThreshold)
                    .tcsRateBelowPct(asPercent(legacyTcsRateBelow))
                    .tcsRateAbovePct(asPercent(legacyTcsRateAbove))
                    .tds194cPct(asPercent(legacyTds194c))
                    .tds194hPct(asPercent(legacyTds194h))
                    .tds194jPct(asPercent(legacyTds194j))
                    .tdsNoPanPct(asPercent(legacyTdsNoPan))
                    .build());
        }
        hsnSacRateService.ensureDefaults(tenantId);
        return s;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Company company(Long tenantId) {
        return companyRepository.findByTenantId(tenantId).orElse(null);
    }

    /** 0.05 (fraction) → 5.00 (percent). */
    private static BigDecimal asPercent(BigDecimal fraction) {
        BigDecimal f = fraction != null ? fraction : new BigDecimal("0.05");
        return f.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private AccountingSettingsDto toDto(AccountingSettings s, Company co) {
        String gstin = co != null ? co.getGstin() : null;
        String stateCode = GstStateCodes.fromGstin(gstin);
        if (stateCode == null && co != null) stateCode = GstStateCodes.fromStateName(co.getState());
        return AccountingSettingsDto.builder()
                .gstScheme(s.getGstScheme())
                .canIssueTaxInvoice(s.getGstScheme().canIssueTaxInvoice())
                .autoTcsOnOverseas(s.isAutoTcsOnOverseas())
                .roundInvoiceTotal(s.isRoundInvoiceTotal())
                .inputTaxCreditEligible(s.isInputTaxCreditEligible())
                .supplierGstin(gstin)
                .supplierStateCode(stateCode)
                .applyGstOnBookings(s.isApplyGstOnBookings())
                .bookingGstRatePct(s.getBookingGstRatePct())
                .tcsApplicability(s.getTcsApplicability())
                .bookingTcsRatePct(s.getBookingTcsRatePct())
                .tcsThreshold(s.getTcsThreshold())
                .tcsRateBelowPct(s.getTcsRateBelowPct())
                .tcsRateAbovePct(s.getTcsRateAbovePct())
                .tds194cPct(s.getTds194cPct())
                .tds194hPct(s.getTds194hPct())
                .tds194jPct(s.getTds194jPct())
                .tdsNoPanPct(s.getTdsNoPanPct())
                .build();
    }
}