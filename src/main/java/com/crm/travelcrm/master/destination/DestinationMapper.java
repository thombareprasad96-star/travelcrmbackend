package com.crm.travelcrm.master.destination;

import org.springframework.stereotype.Component;

@Component
public class DestinationMapper {

    private DestinationMapper() {
        // Utility class
    }

    public static DestinationMasterEntity toEntity(DestinationMasterRequestDTO dto) {
        if (dto == null) {
            return null;
        }

        return DestinationMasterEntity.builder()
                .country(dto.getCountry())
                .name(dto.getName())
                .type(dto.getType())
                .imagePath(dto.getImagePath())
                .inclusions(dto.getInclusions())
                .exclusions(dto.getExclusions())
                .paymentPolicies(dto.getPaymentPolicies())
                .cancellationPolicies(dto.getCancellationPolicies())
                .bookingTerms(dto.getBookingTerms())
                .status(dto.getStatus())
                .build();
    }

    public static DestinationMasterResponseDTO toResponseDTO(DestinationMasterEntity entity) {
        if (entity == null) {
            return null;
        }

        return DestinationMasterResponseDTO.builder()
                .id(entity.getId())
                .country(entity.getCountry())
                .name(entity.getName())
                .type(entity.getType())
                .imagePath(entity.getImagePath())
                .inclusions(entity.getInclusions())
                .exclusions(entity.getExclusions())
                .paymentPolicies(entity.getPaymentPolicies())
                .cancellationPolicies(entity.getCancellationPolicies())
                .bookingTerms(entity.getBookingTerms())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static void updateEntity(DestinationMasterEntity entity, DestinationMasterRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setCountry(dto.getCountry());
        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setImagePath(dto.getImagePath());
        entity.setInclusions(dto.getInclusions());
        entity.setExclusions(dto.getExclusions());
        entity.setPaymentPolicies(dto.getPaymentPolicies());
        entity.setCancellationPolicies(dto.getCancellationPolicies());
        entity.setBookingTerms(dto.getBookingTerms());
        entity.setStatus(dto.getStatus());
    }
}