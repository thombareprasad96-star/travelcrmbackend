package com.crm.travelcrm.accounting.tax.service;

import com.crm.travelcrm.accounting.tax.dto.HsnSacRateDto;
import com.crm.travelcrm.accounting.tax.dto.HsnSacRateRequest;
import com.crm.travelcrm.accounting.tax.entity.HsnSacRate;
import com.crm.travelcrm.accounting.tax.repository.HsnSacRateRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for the tenant's HSN/SAC rate master, plus resolution helpers used by the GST calculator. On
 * first access the tenant is seeded with the common travel defaults (tour-package 5% no-ITC as the
 * default row, agent-commission 18% with ITC) so invoicing works out of the box; the tenant can then
 * edit/add codes.
 */
@Service
@RequiredArgsConstructor
public class HsnSacRateService {

    private final HsnSacRateRepository repository;

    @Transactional
    public List<HsnSacRateDto> getAll(Long tenantId) {
        ensureDefaults(tenantId);
        return repository.findByTenantIdAndDeletedAtIsNullOrderByCategoryAscCodeAsc(tenantId)
                .stream().map(HsnSacRateService::toDto).toList();
    }

    @Transactional
    public HsnSacRateDto create(HsnSacRateRequest req, Long tenantId) {
        repository.findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, req.getCode().trim())
                .ifPresent(existing -> {
                    throw new BusinessException("An HSN/SAC row for code " + req.getCode()
                            + " already exists.", HttpStatus.CONFLICT);
                });

        boolean makeDefault = Boolean.TRUE.equals(req.getIsDefault());
        if (makeDefault) clearExistingDefault(tenantId);

        HsnSacRate row = HsnSacRate.builder()
                .tenantId(tenantId)
                .code(req.getCode().trim())
                .description(req.getDescription())
                .category(req.getCategory())
                .gstRatePct(req.getGstRatePct() != null ? req.getGstRatePct() : BigDecimal.ZERO)
                .cessPct(req.getCessPct() != null ? req.getCessPct() : BigDecimal.ZERO)
                .itcEligible(Boolean.TRUE.equals(req.getItcEligible()))
                .isDefault(makeDefault)
                .active(req.getActive() == null || req.getActive())
                .build();
        return toDto(repository.save(row));
    }

    @Transactional
    public HsnSacRateDto update(UUID publicId, HsnSacRateRequest req, Long tenantId) {
        HsnSacRate row = require(publicId, tenantId);

        if (req.getCode() != null && !req.getCode().trim().equalsIgnoreCase(row.getCode())) {
            repository.findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, req.getCode().trim())
                    .filter(other -> other.getId() != row.getId())
                    .ifPresent(other -> {
                        throw new BusinessException("An HSN/SAC row for code " + req.getCode()
                                + " already exists.", HttpStatus.CONFLICT);
                    });
            row.setCode(req.getCode().trim());
        }
        if (req.getDescription() != null) row.setDescription(req.getDescription());
        if (req.getCategory() != null)    row.setCategory(req.getCategory());
        if (req.getGstRatePct() != null)  row.setGstRatePct(req.getGstRatePct());
        if (req.getCessPct() != null)     row.setCessPct(req.getCessPct());
        if (req.getItcEligible() != null) row.setItcEligible(req.getItcEligible());
        if (req.getActive() != null)      row.setActive(req.getActive());
        if (Boolean.TRUE.equals(req.getIsDefault()) && !row.isDefault()) {
            clearExistingDefault(tenantId);
            row.setDefault(true);
        } else if (Boolean.FALSE.equals(req.getIsDefault())) {
            row.setDefault(false);
        }
        return toDto(repository.save(row));
    }

    @Transactional
    public void delete(UUID publicId, Long tenantId, String deletedByEmail) {
        HsnSacRate row = require(publicId, tenantId);
        row.softDelete(deletedByEmail);
        repository.save(row);
    }

    /** The tenant's default rate row (used for invoice lines without an explicit code). */
    @Transactional
    public HsnSacRate resolveDefault(Long tenantId) {
        ensureDefaults(tenantId);
        return repository.findFirstByTenantIdAndIsDefaultTrueAndDeletedAtIsNull(tenantId)
                .orElseGet(() -> repository
                        .findByTenantIdAndDeletedAtIsNullOrderByCategoryAscCodeAsc(tenantId)
                        .stream().findFirst()
                        .orElseThrow(() -> new BusinessException(
                                "No HSN/SAC tax rate is configured. Add one under Accounting settings.",
                                HttpStatus.CONFLICT)));
    }

    /** Resolve a specific code for the tenant, or {@code null} if not configured. */
    @Transactional(readOnly = true)
    public HsnSacRate resolveByCodeOrNull(Long tenantId, String code) {
        if (code == null || code.isBlank()) return null;
        return repository.findByTenantIdAndCodeAndDeletedAtIsNull(tenantId, code.trim()).orElse(null);
    }

    // ── Seeding ────────────────────────────────────────────────────────────────

    /** Idempotently seed the common travel defaults the first time a tenant opens accounting. */
    @Transactional
    public void ensureDefaults(Long tenantId) {
        if (repository.countByTenantIdAndDeletedAtIsNull(tenantId) > 0) return;
        repository.save(HsnSacRate.builder().tenantId(tenantId)
                .code("998552").category("Tour Package")
                .description("Tour operator services (package tour)")
                .gstRatePct(new BigDecimal("5.00")).cessPct(BigDecimal.ZERO)
                .itcEligible(false).isDefault(true).active(true).build());
        repository.save(HsnSacRate.builder().tenantId(tenantId)
                .code("998551").category("Commission")
                .description("Reservation services / agent commission")
                .gstRatePct(new BigDecimal("18.00")).cessPct(BigDecimal.ZERO)
                .itcEligible(true).isDefault(false).active(true).build());
        repository.save(HsnSacRate.builder().tenantId(tenantId)
                .code("996311").category("Hotel")
                .description("Hotel accommodation services")
                .gstRatePct(new BigDecimal("12.00")).cessPct(BigDecimal.ZERO)
                .itcEligible(true).isDefault(false).active(true).build());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void clearExistingDefault(Long tenantId) {
        repository.findFirstByTenantIdAndIsDefaultTrueAndDeletedAtIsNull(tenantId)
                .ifPresent(prev -> {
                    prev.setDefault(false);
                    repository.save(prev);
                });
    }

    private HsnSacRate require(UUID publicId, Long tenantId) {
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("HSN/SAC rate not found: " + publicId));
    }

    static HsnSacRateDto toDto(HsnSacRate r) {
        return HsnSacRateDto.builder()
                .publicId(r.getPublicId())
                .code(r.getCode())
                .description(r.getDescription())
                .category(r.getCategory())
                .gstRatePct(r.getGstRatePct())
                .cessPct(r.getCessPct())
                .itcEligible(r.isItcEligible())
                .isDefault(r.isDefault())
                .active(r.isActive())
                .build();
    }
}