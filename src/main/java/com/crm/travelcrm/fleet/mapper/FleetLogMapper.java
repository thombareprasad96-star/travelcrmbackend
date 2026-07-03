package com.crm.travelcrm.fleet.mapper;

import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetFuelLog;
import com.crm.travelcrm.fleet.entity.FleetMaintenanceLog;
import org.mapstruct.*;

/** MapStruct mapper for fuel + maintenance logs. The vehicle association is set by the service. */
@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface FleetLogMapper {

    // ── Fuel ─────────────────────────────────────────────────────────────────

    @Mapping(target = "vehiclePublicId", source = "vehicle.publicId")
    @Mapping(target = "vehicleNumber", source = "vehicle.vehicleNumber")
    FleetFuelLogResponseDto toDto(FleetFuelLog log);

    @Mapping(target = "vehicle", ignore = true)
    FleetFuelLog toEntity(FleetFuelLogRequestDto dto);

    @Mapping(target = "vehicle", ignore = true)
    void updateFuel(FleetFuelLogRequestDto dto, @MappingTarget FleetFuelLog log);

    // ── Maintenance ──────────────────────────────────────────────────────────

    @Mapping(target = "vehiclePublicId", source = "vehicle.publicId")
    @Mapping(target = "vehicleNumber", source = "vehicle.vehicleNumber")
    FleetMaintenanceLogResponseDto toDto(FleetMaintenanceLog log);

    @Mapping(target = "vehicle", ignore = true)
    FleetMaintenanceLog toEntity(FleetMaintenanceLogRequestDto dto);

    @Mapping(target = "vehicle", ignore = true)
    void updateMaintenance(FleetMaintenanceLogRequestDto dto, @MappingTarget FleetMaintenanceLog log);
}