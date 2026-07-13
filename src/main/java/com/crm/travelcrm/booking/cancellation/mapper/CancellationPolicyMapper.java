package com.crm.travelcrm.booking.cancellation.mapper;

import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyBandDto;
import com.crm.travelcrm.booking.cancellation.dto.CancellationPolicyResponse;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicyBand;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hand-written mapper. Deliberately not MapStruct: the create path is not a field copy (it derives
 * the next version, deactivates the prior one) so it lives in the service; only band ↔ dto and
 * entity → response are mechanical, and doing them by hand keeps the immutability rules explicit.
 */
@Component
public class CancellationPolicyMapper {

    public CancellationPolicyBand toBand(CancellationPolicyBandDto dto) {
        return CancellationPolicyBand.builder()
                .minDaysBeforeDeparture(dto.getMinDaysBeforeDeparture())
                .deductionType(dto.getDeductionType())
                .deductionValue(dto.getDeductionValue())
                .build();
    }

    public CancellationPolicyBandDto toBandDto(CancellationPolicyBand band) {
        return CancellationPolicyBandDto.builder()
                .minDaysBeforeDeparture(band.getMinDaysBeforeDeparture())
                .deductionType(band.getDeductionType())
                .deductionValue(band.getDeductionValue())
                .build();
    }

    public List<CancellationPolicyBand> toBands(List<CancellationPolicyBandDto> dtos) {
        return dtos.stream().map(this::toBand).toList();
    }

    public CancellationPolicyResponse toResponse(CancellationPolicy p) {
        return CancellationPolicyResponse.builder()
                .publicId(p.getPublicId())
                .name(p.getName())
                .level(p.getLevel())
                .ownerPublicId(p.getOwnerPublicId())
                .version(p.getVersion())
                .active(p.getActive())
                .effectiveFrom(p.getEffectiveFrom())
                .gstOnChargeApplicable(p.getGstOnChargeApplicable())
                .tcsRefundable(p.getTcsRefundable())
                .bands(p.getBands().stream().map(this::toBandDto).toList())
                .createdBy(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}