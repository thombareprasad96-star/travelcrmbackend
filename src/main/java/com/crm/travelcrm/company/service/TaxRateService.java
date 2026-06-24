package com.crm.travelcrm.company.service;

import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.company.dto.TaxRateCreateRequest;
import com.crm.travelcrm.company.dto.TaxRateDTO;
import com.crm.travelcrm.company.entity.TaxRate;
import com.crm.travelcrm.company.repository.TaxRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaxRateService {

    private final TaxRateRepository taxRateRepository;

    @Transactional(readOnly = true)
    public List<TaxRateDTO> getAll(Long tenantId) {
        return taxRateRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<TaxRateDTO> getActive(Long tenantId) {
        return taxRateRepository.findByTenantIdAndIsActiveTrueOrderByTypeAsc(tenantId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public TaxRateDTO create(TaxRateCreateRequest req, Long tenantId) {
        // Close any currently-active rate of the same type.
        List<TaxRate> previous = taxRateRepository
                .findByTenantIdAndTypeAndIsActiveTrue(tenantId, req.getType());
        previous.forEach(p -> {
            p.setActive(false);
            p.setEffectiveTo(req.getEffectiveFrom().minusDays(1));
        });
        taxRateRepository.saveAll(previous);

        TaxRate rate = TaxRate.builder()
                .type(req.getType())
                .rate(req.getRate())
                .calculation(req.getCalculation() != null ? req.getCalculation() : "Additive")
                .effectiveFrom(req.getEffectiveFrom())
                .description(req.getDescription())
                .isActive(true)
                .build();
        return toDto(taxRateRepository.save(rate));
    }

    @Transactional
    public void delete(UUID publicId, Long tenantId) {
        TaxRate rate = taxRateRepository.findByPublicIdAndTenantId(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tax rate not found: " + publicId));
        taxRateRepository.delete(rate);
    }

    private TaxRateDTO toDto(TaxRate r) {
        return TaxRateDTO.builder()
                .publicId(r.getPublicId())
                .type(r.getType())
                .rate(r.getRate())
                .calculation(r.getCalculation())
                .effectiveFrom(r.getEffectiveFrom() != null ? r.getEffectiveFrom().toString() : null)
                .effectiveTo(r.getEffectiveTo() != null ? r.getEffectiveTo().toString() : null)
                .description(r.getDescription())
                .isActive(r.isActive())
                .createdAt(r.getCreatedAt())
                .build();
    }
}