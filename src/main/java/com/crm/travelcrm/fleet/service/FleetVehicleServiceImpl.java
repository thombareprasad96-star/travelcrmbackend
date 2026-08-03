package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.exception.RestoreAvailableException;
import com.crm.travelcrm.fleet.dto.FleetOptionDto;
import com.crm.travelcrm.fleet.dto.FleetVehicleRequestDto;
import com.crm.travelcrm.fleet.dto.FleetVehicleResponseDto;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.integration.spi.FleetParty;
import com.crm.travelcrm.fleet.integration.spi.FleetPartyPort;
import com.crm.travelcrm.fleet.mapper.FleetVehicleMapper;
import com.crm.travelcrm.fleet.repository.*;
import com.crm.travelcrm.fleet.specification.FleetVehicleSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FleetVehicleServiceImpl implements FleetVehicleService {

    private final FleetVehicleRepository vehicleRepository;
    private final FleetTripRepository tripRepository;
    private final FleetFuelLogRepository fuelLogRepository;
    private final FleetMaintenanceLogRepository maintenanceLogRepository;
    private final FleetExpenseRepository expenseRepository;
    private final FleetComplianceDocumentRepository documentRepository;
    private final FleetTripLegRepository legRepository;
    private final FleetPartyPort partyPort;
    private final FleetVehicleMapper mapper;

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetVehicleResponseDto create(FleetVehicleRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        String number = normalize(request.getVehicleNumber());

        if (vehicleRepository.existsByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNull(tenantId, number)) {
            throw new BusinessException("Vehicle number '" + number + "' already exists", HttpStatus.CONFLICT);
        }
        // Trashed-only collision → offer restore instead of a duplicate (Lead/Customer pattern).
        vehicleRepository.findFirstByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNotNull(tenantId, number)
                .ifPresent(trashed -> {
                    throw new RestoreAvailableException(
                            "Vehicle '" + number + "' is in Trash — restore it instead of creating a duplicate",
                            "FLEET_VEHICLE", trashed.getPublicId());
                });

        FleetVehicle vehicle = mapper.toEntity(request);
        vehicle.setVehicleNumber(number);
        if (vehicle.getOwnerType() == null) vehicle.setOwnerType(FleetOwnerType.OWN);
        if (vehicle.getStatus() == null) vehicle.setStatus(FleetVehicleStatus.AVAILABLE);
        applyVendor(vehicle, request.getVendorPublicId(), tenantId);

        FleetVehicle saved = vehicleRepository.save(vehicle);
        log.info("Fleet vehicle created | id: {} | number: {} | tenantId: {}",
                saved.getId(), saved.getVehicleNumber(), tenantId);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetVehicleResponseDto update(UUID publicId, FleetVehicleRequestDto request) {
        FleetVehicle vehicle = findOrThrow(publicId);

        if (StringUtils.hasText(request.getVehicleNumber())) {
            String number = normalize(request.getVehicleNumber());
            if (!number.equalsIgnoreCase(vehicle.getVehicleNumber())
                    && vehicleRepository.existsByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNullAndIdNot(
                            vehicle.getTenantId(), number, vehicle.getId())) {
                throw new BusinessException("Vehicle number '" + number + "' already exists", HttpStatus.CONFLICT);
            }
            vehicle.setVehicleNumber(number);
        }

        mapper.updateEntity(request, vehicle);

        if (request.getVendorPublicId() != null) {
            applyVendor(vehicle, request.getVendorPublicId(), vehicle.getTenantId());
        }
        // An agency-owned vehicle carries no vendor link.
        if (vehicle.getOwnerType() == FleetOwnerType.OWN) {
            vehicle.setVendorId(null);
            vehicle.setVendorPublicId(null);
            vehicle.setVendorName(null);
        }

        FleetVehicle saved = vehicleRepository.save(vehicle);
        log.info("Fleet vehicle updated | id: {} | tenantId: {}", saved.getId(), saved.getTenantId());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetVehicleResponseDto changeStatus(UUID publicId, String status) {
        FleetVehicle vehicle = findOrThrow(publicId);
        FleetVehicleStatus target = parseVehicleStatus(status);
        if (target == null) {
            throw new BusinessException("Unknown vehicle status: " + status, HttpStatus.BAD_REQUEST);
        }
        if (target == FleetVehicleStatus.ON_TRIP) {
            throw new BusinessException(
                    "ON_TRIP is managed by the trip lifecycle — start a trip instead", HttpStatus.BAD_REQUEST);
        }
        if (tripRepository.existsByVehicle_IdAndStatusAndDeletedAtIsNull(vehicle.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException(
                    "Vehicle has an ongoing trip — close or cancel it first", HttpStatus.CONFLICT);
        }
        vehicle.setStatus(target);
        FleetVehicle saved = vehicleRepository.save(vehicle);
        log.info("Fleet vehicle status changed | id: {} | status: {}", saved.getId(), target);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        FleetVehicle vehicle = findOrThrow(publicId);
        if (tripRepository.existsByVehicle_IdAndStatusAndDeletedAtIsNull(vehicle.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException(
                    "Vehicle has an ongoing trip — close or cancel it first", HttpStatus.CONFLICT);
        }
        // Diary history is never orphaned: a vehicle with active trip/fuel/service rows can't be
        // trashed (mirror of MasterReferenceGuard) — retire it with OUT_OF_SERVICE instead. This
        // also keeps the 30-day trash purge FK-safe (children are always trashed before the parent).
        if (tripRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())
                || fuelLogRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())
                || maintenanceLogRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())) {
            throw new BusinessException(
                    "Vehicle has diary records (trips / fuel / service logs) — mark it OUT_OF_SERVICE instead of deleting",
                    HttpStatus.CONFLICT);
        }
        // Financial and statutory records are a HARDER stop than diary history.
        //
        // fleet_expenses and fleet_compliance_documents are deliberately absent from TrashableType,
        // because their retention requirement is eight years and the 30-day purge would hard-delete
        // them. But excluding the children is not enough on its own: this vehicle IS registered, so
        // trashing it would let the purge reach the children through the FK — either blowing up the
        // whole tenant's nightly purge on a constraint violation, or, worse, taking three years of
        // records with it. The guard has to live here.
        if (expenseRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())
                || documentRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())
                || legRepository.existsByVehicle_IdAndDeletedAtIsNull(vehicle.getId())) {
            throw new BusinessException(
                    "This vehicle carries expenses or compliance documents, which are kept for eight "
                            + "years and can never be purged. Mark it OUT_OF_SERVICE instead.",
                    HttpStatus.CONFLICT);
        }
        vehicle.softDelete(FleetContext.username());
        vehicleRepository.save(vehicle);
        log.info("Fleet vehicle soft-deleted | id: {} | tenantId: {}", vehicle.getId(), vehicle.getTenantId());
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FleetVehicleResponseDto getByPublicId(UUID publicId) {
        return mapper.toDto(findOrThrow(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetVehicleResponseDto> list(
            String status, String ownerType, String search, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        var spec = FleetVehicleSpecification.build(
                tenantId, parseVehicleStatus(status), parseOwnerType(ownerType), search);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FleetVehicle> result = vehicleRepository.findAll(spec, pageable);
        List<FleetVehicleResponseDto> data = result.map(mapper::toDto).getContent();
        return PagedApiResponse.of("Vehicles fetched successfully", data, PaginationMeta.from(result));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetOptionDto> options(String status) {
        Long tenantId = FleetContext.tenantId();
        FleetVehicleStatus parsed = parseVehicleStatus(status);
        List<FleetVehicle> vehicles = (parsed == null)
                ? vehicleRepository.findByTenantIdAndDeletedAtIsNullOrderByVehicleNumberAsc(tenantId)
                : vehicleRepository.findByTenantIdAndStatusAndDeletedAtIsNullOrderByVehicleNumberAsc(tenantId, parsed);
        return vehicles.stream()
                .map(v -> new FleetOptionDto(v.getPublicId(), optionLabel(v), v.getStatus().name()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetOptionDto> vendorOptions() {
        // Empty in a Fleet-only deployment — the form renders without the owner dropdown rather
        // than erroring. See StandalonePartyPort.
        return partyPort.options(FleetContext.tenantId())
                .stream()
                .map(p -> new FleetOptionDto(p.publicId(), p.name(), null))
                .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetVehicle findOrThrow(UUID publicId) {
        return vehicleRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + publicId));
    }

    /**
     * Resolves the vehicle's owner through {@link FleetPartyPort} instead of importing
     * {@code Vendor}, so this compiles and runs with no vendor module present. CRM behaviour is
     * unchanged — the adapter runs the same tenant-scoped finder this method used to call directly.
     */
    private void applyVendor(FleetVehicle vehicle, UUID vendorPublicId, Long tenantId) {
        if (vendorPublicId == null) return;
        FleetParty party = partyPort.resolve(vendorPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found: " + vendorPublicId));
        vehicle.setVendorId(party.id());
        vehicle.setVendorPublicId(party.publicId());
        vehicle.setVendorName(party.name());
    }

    /** Trim, collapse inner whitespace, uppercase — "mh 12 ab 1234" → "MH 12 AB 1234". */
    private String normalize(String vehicleNumber) {
        return vehicleNumber.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    private String optionLabel(FleetVehicle v) {
        String detail = StringUtils.hasText(v.getModel()) ? v.getModel() : v.getType();
        return StringUtils.hasText(detail) ? v.getVehicleNumber() + " · " + detail : v.getVehicleNumber();
    }

    private FleetVehicleStatus parseVehicleStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return FleetVehicleStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private FleetOwnerType parseOwnerType(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return FleetOwnerType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}