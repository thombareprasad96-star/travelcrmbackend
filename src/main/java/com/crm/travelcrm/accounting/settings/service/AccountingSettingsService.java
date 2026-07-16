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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
            s = repository.save(AccountingSettings.builder().gstScheme(scheme).build());
        }
        hsnSacRateService.ensureDefaults(tenantId);
        return s;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Company company(Long tenantId) {
        return companyRepository.findByTenantId(tenantId).orElse(null);
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
                .build();
    }
}