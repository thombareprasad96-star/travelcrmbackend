package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetFuelLog;
import com.crm.travelcrm.fleet.entity.FleetMaintenanceLog;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.mapper.FleetLogMapper;
import com.crm.travelcrm.fleet.repository.FleetFuelLogRepository;
import com.crm.travelcrm.fleet.repository.FleetMaintenanceLogRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fuel + maintenance logs. Every write also maintains the vehicle's denormalized fields:
 * {@code lastOdometer} (bump-up only) and — for maintenance — {@code nextServiceDueDate/Km},
 * recomputed from the latest remaining log so the dashboard never scans the log table.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetLogServiceImpl implements FleetLogService {

    private final FleetVehicleRepository vehicleRepository;
    private final FleetFuelLogRepository fuelLogRepository;
    private final FleetMaintenanceLogRepository maintenanceLogRepository;
    private final FleetLogMapper mapper;

    // ── Fuel ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetFuelLogResponseDto addFuelLog(UUID vehiclePublicId, FleetFuelLogRequestDto request) {
        FleetVehicle vehicle = findVehicle(vehiclePublicId);
        FleetFuelLog fuelLog = mapper.toEntity(request);
        fuelLog.setVehicle(vehicle);
        FleetFuelLog saved = fuelLogRepository.save(fuelLog);
        vehicle.bumpOdometer(request.getOdometer());
        log.info("Fleet fuel log added | id: {} | vehicle: {}", saved.getId(), vehicle.getVehicleNumber());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetFuelLogResponseDto updateFuelLog(UUID publicId, FleetFuelLogRequestDto request) {
        FleetFuelLog fuelLog = fuelLogRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Fuel log not found: " + publicId));
        mapper.updateFuel(request, fuelLog);
        FleetFuelLog saved = fuelLogRepository.save(fuelLog);
        fuelLog.getVehicle().bumpOdometer(fuelLog.getOdometer());
        log.info("Fleet fuel log updated | id: {}", saved.getId());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public void deleteFuelLog(UUID publicId) {
        FleetFuelLog fuelLog = fuelLogRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Fuel log not found: " + publicId));
        // lastOdometer is intentionally NOT recomputed downward on delete — bump-up only.
        fuelLog.softDelete(FleetContext.username());
        fuelLogRepository.save(fuelLog);
        log.info("Fleet fuel log soft-deleted | id: {}", fuelLog.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetFuelLogResponseDto> listFuelLogs(UUID vehiclePublicId, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        findVehicle(vehiclePublicId);   // 404 on unknown/foreign vehicle, never an empty page
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<FleetFuelLog> result = fuelLogRepository
                .findByVehicle_PublicIdAndTenantIdAndDeletedAtIsNull(vehiclePublicId, tenantId, pageable);
        List<FleetFuelLogResponseDto> data = result.map(mapper::toDto).getContent();
        return PagedApiResponse.of("Fuel logs fetched successfully", data, PaginationMeta.from(result));
    }

    // ── Maintenance ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetMaintenanceLogResponseDto addMaintenanceLog(
            UUID vehiclePublicId, FleetMaintenanceLogRequestDto request) {
        FleetVehicle vehicle = findVehicle(vehiclePublicId);
        FleetMaintenanceLog maintenanceLog = mapper.toEntity(request);
        maintenanceLog.setVehicle(vehicle);
        FleetMaintenanceLog saved = maintenanceLogRepository.save(maintenanceLog);
        vehicle.bumpOdometer(request.getOdometer());
        recomputeNextService(vehicle);
        log.info("Fleet maintenance log added | id: {} | vehicle: {}", saved.getId(), vehicle.getVehicleNumber());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetMaintenanceLogResponseDto updateMaintenanceLog(
            UUID publicId, FleetMaintenanceLogRequestDto request) {
        FleetMaintenanceLog maintenanceLog = maintenanceLogRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Maintenance log not found: " + publicId));
        mapper.updateMaintenance(request, maintenanceLog);
        FleetMaintenanceLog saved = maintenanceLogRepository.save(maintenanceLog);
        FleetVehicle vehicle = maintenanceLog.getVehicle();
        vehicle.bumpOdometer(maintenanceLog.getOdometer());
        recomputeNextService(vehicle);
        log.info("Fleet maintenance log updated | id: {}", saved.getId());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public void deleteMaintenanceLog(UUID publicId) {
        FleetMaintenanceLog maintenanceLog = maintenanceLogRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Maintenance log not found: " + publicId));
        maintenanceLog.softDelete(FleetContext.username());
        maintenanceLogRepository.save(maintenanceLog);
        recomputeNextService(maintenanceLog.getVehicle());
        log.info("Fleet maintenance log soft-deleted | id: {}", maintenanceLog.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetMaintenanceLogResponseDto> listMaintenanceLogs(
            UUID vehiclePublicId, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        findVehicle(vehiclePublicId);
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "serviceDate").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<FleetMaintenanceLog> result = maintenanceLogRepository
                .findByVehicle_PublicIdAndTenantIdAndDeletedAtIsNull(vehiclePublicId, tenantId, pageable);
        List<FleetMaintenanceLogResponseDto> data = result.map(mapper::toDto).getContent();
        return PagedApiResponse.of("Maintenance logs fetched successfully", data, PaginationMeta.from(result));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetVehicle findVehicle(UUID publicId) {
        return vehicleRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + publicId));
    }

    /** Latest remaining log (by serviceDate) wins — including clearing the fields when none remain. */
    private void recomputeNextService(FleetVehicle vehicle) {
        var latest = maintenanceLogRepository
                .findFirstByVehicle_IdAndDeletedAtIsNullOrderByServiceDateDescIdDesc(vehicle.getId());
        vehicle.setNextServiceDueDate(latest.map(FleetMaintenanceLog::getNextServiceDueDate).orElse(null));
        vehicle.setNextServiceDueKm(latest.map(FleetMaintenanceLog::getNextServiceDueKm).orElse(null));
    }
}