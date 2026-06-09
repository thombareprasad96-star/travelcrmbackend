package com.crm.travelcrm.master.city;

import org.springframework.stereotype.Component;

@Component
public class CityMapper {

    private CityMapper() {
        // Utility class
    }

    public static CityMasterEntity toEntity(CityMasterRequestDTO dto) {
        if (dto == null) {
            return null;
        }

        return CityMasterEntity.builder()
                .country(dto.getCountry())
                .city(dto.getName())
                .airportCode(dto.getCode())
                .status(dto.getStatus())
                .build();
    }

    public static CityMasterResponseDTO toResponseDTO(CityMasterEntity entity) {
        if (entity == null) {
            return null;
        }

        return CityMasterResponseDTO.builder()
                .id(entity.getId())
                .country(entity.getCountry())
                .name(entity.getCity())
                .code(entity.getAirportCode())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static void updateEntity(CityMasterEntity entity, CityMasterRequestDTO dto) {
        if (entity == null || dto == null) {
            return;
        }

        entity.setCountry(dto.getCountry());
        entity.setCity(dto.getName());
        entity.setAirportCode(dto.getCode());
        entity.setStatus(dto.getStatus());
    }
}