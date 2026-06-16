package com.crm.travelcrm.master.vehicle;

import org.springframework.stereotype.Component;

@Component
public class VehicleMapper {

    private VehicleMapper() {}

    public static VehicleEntity toEntity(VehicleRequestDTO dto) {
        if (dto == null) return null;

        return VehicleEntity.builder()
                .name(dto.getName())
                .type(dto.getType())
                .capacity(dto.getCapacity())
                .description(dto.getDescription())
                .imagePath(dto.getImagePath())
                .build();
    }

    public static VehicleResponseDTO toResponseDTO(VehicleEntity entity) {
        if (entity == null) return null;

        return VehicleResponseDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .type(entity.getType())
                .capacity(entity.getCapacity())
                .description(entity.getDescription())
                .imagePath(entity.getImagePath())
                .global(entity.getTenantId() == null)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static void updateEntity(VehicleEntity entity, VehicleRequestDTO dto) {
        if (entity == null || dto == null) return;

        entity.setName(dto.getName());
        entity.setType(dto.getType());
        entity.setCapacity(dto.getCapacity());
        entity.setDescription(dto.getDescription());
        entity.setImagePath(dto.getImagePath());
    }
}