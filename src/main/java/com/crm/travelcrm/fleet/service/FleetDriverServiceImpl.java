package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetDriverRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDriverResponseDto;
import com.crm.travelcrm.fleet.dto.FleetOptionDto;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.mapper.FleetDriverMapper;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.specification.FleetDriverSpecification;
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
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FleetDriverServiceImpl implements FleetDriverService {

    private final FleetDriverRepository driverRepository;
    private final FleetTripRepository tripRepository;
    private final FleetDriverMapper mapper;

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetDriverResponseDto create(FleetDriverRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetDriver driver = mapper.toEntity(request);
        if (driver.getStatus() == null) driver.setStatus(FleetDriverStatus.ACTIVE);
        FleetDriver saved = driverRepository.save(driver);
        log.info("Fleet driver created | id: {} | tenantId: {}", saved.getId(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public FleetDriverResponseDto update(UUID publicId, FleetDriverRequestDto request) {
        FleetDriver driver = findOrThrow(publicId);
        mapper.updateEntity(request, driver);
        FleetDriver saved = driverRepository.save(driver);
        log.info("Fleet driver updated | id: {} | tenantId: {}", saved.getId(), saved.getTenantId());
        return toDto(saved);
    }

    @Override
    @Transactional
    public FleetDriverResponseDto changeStatus(UUID publicId, String status) {
        FleetDriver driver = findOrThrow(publicId);
        FleetDriverStatus target = parseStatus(status);
        if (target == null) {
            throw new BusinessException("Unknown driver status: " + status, HttpStatus.BAD_REQUEST);
        }
        if (target == FleetDriverStatus.INACTIVE
                && tripRepository.existsByDriver_IdAndStatusAndDeletedAtIsNull(driver.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException(
                    "Driver is on an ongoing trip — close or cancel it first", HttpStatus.CONFLICT);
        }
        driver.setStatus(target);
        FleetDriver saved = driverRepository.save(driver);
        log.info("Fleet driver status changed | id: {} | status: {}", saved.getId(), target);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        FleetDriver driver = findOrThrow(publicId);
        if (tripRepository.existsByDriver_IdAndStatusAndDeletedAtIsNull(driver.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException(
                    "Driver is on an ongoing trip — close or cancel it first", HttpStatus.CONFLICT);
        }
        // Same purge-safety rule as vehicles: trip history keeps its driver row; retire with INACTIVE.
        if (tripRepository.existsByDriver_IdAndDeletedAtIsNull(driver.getId())) {
            throw new BusinessException(
                    "Driver has trip history — mark them INACTIVE instead of deleting", HttpStatus.CONFLICT);
        }
        driver.softDelete(FleetContext.username());
        driverRepository.save(driver);
        log.info("Fleet driver soft-deleted | id: {} | tenantId: {}", driver.getId(), driver.getTenantId());
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FleetDriverResponseDto getByPublicId(UUID publicId) {
        return toDto(findOrThrow(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetDriverResponseDto> list(String status, String search, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        var spec = FleetDriverSpecification.build(tenantId, parseStatus(status), search);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "name"));
        Page<FleetDriver> result = driverRepository.findAll(spec, pageable);

        // Batch "on trip" flags — one query for the whole page instead of one per driver.
        Set<Long> onTripIds = tripRepository.findDriverIdsByStatus(tenantId, FleetTripStatus.ONGOING);
        List<FleetDriverResponseDto> data = result.map(d -> toDto(d, onTripIds)).getContent();
        return PagedApiResponse.of("Drivers fetched successfully", data, PaginationMeta.from(result));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetOptionDto> options(String status) {
        Long tenantId = FleetContext.tenantId();
        FleetDriverStatus parsed = parseStatus(status);
        List<FleetDriver> drivers = (parsed == null)
                ? driverRepository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId)
                : driverRepository.findByTenantIdAndStatusAndDeletedAtIsNullOrderByNameAsc(tenantId, parsed);
        return drivers.stream()
                .map(d -> new FleetOptionDto(d.getPublicId(), optionLabel(d), d.getStatus().name()))
                .toList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetDriver findOrThrow(UUID publicId) {
        return driverRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + publicId));
    }

    private FleetDriverResponseDto toDto(FleetDriver driver) {
        FleetDriverResponseDto dto = mapper.toDto(driver);
        dto.setOnTrip(tripRepository.existsByDriver_IdAndStatusAndDeletedAtIsNull(
                driver.getId(), FleetTripStatus.ONGOING));
        return dto;
    }

    private FleetDriverResponseDto toDto(FleetDriver driver, Set<Long> onTripIds) {
        FleetDriverResponseDto dto = mapper.toDto(driver);
        dto.setOnTrip(onTripIds.contains(driver.getId()));
        return dto;
    }

    private String optionLabel(FleetDriver d) {
        return StringUtils.hasText(d.getPhone()) ? d.getName() + " · " + d.getPhone() : d.getName();
    }

    private FleetDriverStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return FleetDriverStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}