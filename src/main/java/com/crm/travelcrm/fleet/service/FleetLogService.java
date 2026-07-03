package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;

import java.util.UUID;

/** Fuel + maintenance diary entries, always scoped under a vehicle. */
public interface FleetLogService {

    // ── Fuel ─────────────────────────────────────────────────────────────────

    FleetFuelLogResponseDto addFuelLog(UUID vehiclePublicId, FleetFuelLogRequestDto request);

    FleetFuelLogResponseDto updateFuelLog(UUID publicId, FleetFuelLogRequestDto request);

    void deleteFuelLog(UUID publicId);

    PagedApiResponse<FleetFuelLogResponseDto> listFuelLogs(UUID vehiclePublicId, int page, int size);

    // ── Maintenance ──────────────────────────────────────────────────────────

    FleetMaintenanceLogResponseDto addMaintenanceLog(UUID vehiclePublicId, FleetMaintenanceLogRequestDto request);

    FleetMaintenanceLogResponseDto updateMaintenanceLog(UUID publicId, FleetMaintenanceLogRequestDto request);

    void deleteMaintenanceLog(UUID publicId);

    PagedApiResponse<FleetMaintenanceLogResponseDto> listMaintenanceLogs(UUID vehiclePublicId, int page, int size);
}