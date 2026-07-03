package com.crm.travelcrm.fleet.mapper;

import com.crm.travelcrm.fleet.dto.FleetVehicleRequestDto;
import com.crm.travelcrm.fleet.dto.FleetVehicleResponseDto;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import org.mapstruct.*;

/**
 * MapStruct mapper for {@link FleetVehicle}. The vehicle number (normalized) and the
 * vendor link (validated) are applied by the service — never auto-mapped from the request.
 */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface FleetVehicleMapper {

    FleetVehicleResponseDto toDto(FleetVehicle vehicle);

    @Mapping(target = "vehicleNumber", ignore = true)
    @Mapping(target = "vendorPublicId", ignore = true)
    FleetVehicle toEntity(FleetVehicleRequestDto dto);

    /**
     * Full-replace of the profile fields the form owns — a null in the request CLEARS the
     * field (the FE always sends the whole form, so "cleared insurance date" must stick).
     * Entity-managed fields (status, vendor snapshot, odometer, next-service) are untouched.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL)
    @Mapping(target = "vehicleNumber", ignore = true)
    @Mapping(target = "vendorPublicId", ignore = true)
    void updateEntity(FleetVehicleRequestDto dto, @MappingTarget FleetVehicle vehicle);
}