package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.mapper.FleetTripMapper;
import com.crm.travelcrm.fleet.repository.FleetBookingLookupRepository;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import com.crm.travelcrm.fleet.specification.FleetTripSpecification;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FleetTripServiceImpl implements FleetTripService {

    private final FleetTripRepository tripRepository;
    private final FleetVehicleRepository vehicleRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetBookingLookupRepository bookingLookupRepository;
    private final FleetTripMapper mapper;

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetTripResponseDto create(FleetTripCreateDto request) {
        Long tenantId = FleetContext.tenantId();

        FleetVehicle vehicle = resolveVehicle(request.getVehiclePublicId(), tenantId);
        FleetDriver driver = resolveActiveDriver(request.getDriverPublicId(), tenantId);

        FleetTrip trip = mapper.toEntity(request);
        trip.setVehicle(vehicle);
        trip.setDriver(driver);
        applyBooking(trip, request.getBookingPublicId(), tenantId);
        validateChronology(trip);

        if (trip.getEndDatetime() != null) {
            // Post-facto diary entry — the trip already happened; no vehicle status changes.
            trip.setStatus(FleetTripStatus.COMPLETED);
            computeDistance(trip);
            vehicle.bumpOdometer(trip.getEndOdometer());
        } else {
            trip.setStatus(FleetTripStatus.PLANNED);
        }

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip created | id: {} | status: {} | vehicle: {} | tenantId: {}",
                saved.getId(), saved.getStatus(), vehicle.getVehicleNumber(), tenantId);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto start(UUID publicId, FleetTripStartDto request) {
        FleetTrip trip = findOrThrow(publicId);
        requireStatus(trip, "started", FleetTripStatus.PLANNED);

        FleetVehicle vehicle = trip.getVehicle();
        FleetDriver driver = trip.getDriver();
        if (vehicle.isDeleted()) {
            throw new BusinessException("Vehicle is in Trash — assign another vehicle", HttpStatus.CONFLICT);
        }
        if (vehicle.getStatus() != FleetVehicleStatus.AVAILABLE) {
            throw new BusinessException(
                    "Vehicle is " + vehicle.getStatus() + " — not available for a trip", HttpStatus.CONFLICT);
        }
        if (driver.isDeleted() || driver.getStatus() != FleetDriverStatus.ACTIVE) {
            throw new BusinessException("Driver is not active", HttpStatus.CONFLICT);
        }
        if (tripRepository.existsByVehicle_IdAndStatusAndDeletedAtIsNull(vehicle.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException("Vehicle already has an ongoing trip", HttpStatus.CONFLICT);
        }
        if (tripRepository.existsByDriver_IdAndStatusAndDeletedAtIsNull(driver.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException("Driver is already on an ongoing trip", HttpStatus.CONFLICT);
        }

        trip.setStartOdometer(request.getStartOdometer());
        trip.setStartDatetime(request.getStartDatetime() != null
                ? request.getStartDatetime() : LocalDateTime.now());
        trip.setStatus(FleetTripStatus.ONGOING);
        vehicle.setStatus(FleetVehicleStatus.ON_TRIP);
        vehicle.bumpOdometer(request.getStartOdometer());

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip started | id: {} | vehicle: {} | odometer: {}",
                saved.getId(), vehicle.getVehicleNumber(), request.getStartOdometer());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto close(UUID publicId, FleetTripCloseDto request) {
        FleetTrip trip = findOrThrow(publicId);
        requireStatus(trip, "closed", FleetTripStatus.ONGOING);

        LocalDateTime end = request.getEndDatetime() != null ? request.getEndDatetime() : LocalDateTime.now();
        if (trip.getStartOdometer() != null && request.getEndOdometer() < trip.getStartOdometer()) {
            throw new BusinessException(
                    "End odometer cannot be less than the start odometer", HttpStatus.BAD_REQUEST);
        }
        if (!end.isAfter(trip.getStartDatetime())) {
            throw new BusinessException("End time must be after the start time", HttpStatus.BAD_REQUEST);
        }

        trip.setEndOdometer(request.getEndOdometer());
        trip.setEndDatetime(end);
        if (request.getFuelCost() != null) trip.setFuelCost(request.getFuelCost());
        if (request.getTollCost() != null) trip.setTollCost(request.getTollCost());
        if (request.getDriverAllowance() != null) trip.setDriverAllowance(request.getDriverAllowance());
        if (StringUtils.hasText(request.getRemarks())) trip.setRemarks(request.getRemarks());
        computeDistance(trip);
        trip.setStatus(FleetTripStatus.COMPLETED);

        FleetVehicle vehicle = trip.getVehicle();
        if (vehicle.getStatus() == FleetVehicleStatus.ON_TRIP) {
            vehicle.setStatus(FleetVehicleStatus.AVAILABLE);
        }
        vehicle.bumpOdometer(request.getEndOdometer());

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip closed | id: {} | distanceKm: {}", saved.getId(), saved.getDistanceKm());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto cancel(UUID publicId) {
        FleetTrip trip = findOrThrow(publicId);
        requireStatus(trip, "cancelled", FleetTripStatus.PLANNED, FleetTripStatus.ONGOING);

        if (trip.getStatus() == FleetTripStatus.ONGOING
                && trip.getVehicle().getStatus() == FleetVehicleStatus.ON_TRIP) {
            trip.getVehicle().setStatus(FleetVehicleStatus.AVAILABLE);
        }
        trip.setStatus(FleetTripStatus.CANCELLED);
        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip cancelled | id: {}", saved.getId());
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto update(UUID publicId, FleetTripUpdateDto request) {
        FleetTrip trip = findOrThrow(publicId);
        Long tenantId = trip.getTenantId();

        if (request.getVehiclePublicId() != null || request.getDriverPublicId() != null) {
            if (trip.getStatus() != FleetTripStatus.PLANNED) {
                throw new BusinessException(
                        "Vehicle/driver can only be changed while the trip is planned", HttpStatus.CONFLICT);
            }
            if (request.getVehiclePublicId() != null) {
                trip.setVehicle(resolveVehicle(request.getVehiclePublicId(), tenantId));
            }
            if (request.getDriverPublicId() != null) {
                trip.setDriver(resolveActiveDriver(request.getDriverPublicId(), tenantId));
            }
        }

        mapper.updateEntity(request, trip);
        if (request.getBookingPublicId() != null) {
            applyBooking(trip, request.getBookingPublicId(), tenantId);
        }
        validateChronology(trip);
        computeDistance(trip);
        // Only a finished trip's reading is real — a planned end odometer must not
        // pollute the vehicle's bump-up-only lastOdometer.
        if (trip.getStatus() == FleetTripStatus.COMPLETED) {
            trip.getVehicle().bumpOdometer(trip.getEndOdometer());
        }

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip updated | id: {} | tenantId: {}", saved.getId(), tenantId);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        FleetTrip trip = findOrThrow(publicId);
        if (trip.getStatus() == FleetTripStatus.ONGOING) {
            throw new BusinessException(
                    "Trip is ongoing — close or cancel it before deleting", HttpStatus.CONFLICT);
        }
        trip.softDelete(FleetContext.username());
        tripRepository.save(trip);
        log.info("Fleet trip soft-deleted | id: {} | tenantId: {}", trip.getId(), trip.getTenantId());
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FleetTripResponseDto getByPublicId(UUID publicId) {
        return mapper.toDto(findOrThrow(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetTripResponseDto> list(
            UUID vehiclePublicId, UUID driverPublicId, String status, UUID bookingPublicId,
            LocalDate fromDate, LocalDate toDate, String search, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        var spec = FleetTripSpecification.build(tenantId, vehiclePublicId, driverPublicId,
                parseStatus(status), bookingPublicId, fromDate, toDate, search);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startDatetime"));
        Page<FleetTrip> result = tripRepository.findAll(spec, pageable);
        List<FleetTripResponseDto> data = result.map(mapper::toDto).getContent();
        return PagedApiResponse.of("Trips fetched successfully", data, PaginationMeta.from(result));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetTrip findOrThrow(UUID publicId) {
        return tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + publicId));
    }

    private FleetVehicle resolveVehicle(UUID publicId, Long tenantId) {
        return vehicleRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + publicId));
    }

    private FleetDriver resolveActiveDriver(UUID publicId, Long tenantId) {
        FleetDriver driver = driverRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + publicId));
        if (driver.getStatus() != FleetDriverStatus.ACTIVE) {
            throw new BusinessException("Driver '" + driver.getName() + "' is inactive", HttpStatus.BAD_REQUEST);
        }
        return driver;
    }

    /** Resolves + tenant-validates the booking link and snapshots publicId/code for display. */
    private void applyBooking(FleetTrip trip, UUID bookingPublicId, Long tenantId) {
        if (bookingPublicId == null) return;
        Booking booking = bookingLookupRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(bookingPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));
        trip.setBookingId(booking.getId());
        trip.setBookingPublicId(booking.getPublicId());
        trip.setBookingCode(booking.getBookingCode());
    }

    private void validateChronology(FleetTrip trip) {
        if (trip.getStartOdometer() != null && trip.getEndOdometer() != null
                && trip.getEndOdometer() < trip.getStartOdometer()) {
            throw new BusinessException(
                    "End odometer cannot be less than the start odometer", HttpStatus.BAD_REQUEST);
        }
        if (trip.getStartDatetime() != null && trip.getEndDatetime() != null
                && !trip.getEndDatetime().isAfter(trip.getStartDatetime())) {
            throw new BusinessException("End time must be after the start time", HttpStatus.BAD_REQUEST);
        }
    }

    private void computeDistance(FleetTrip trip) {
        if (trip.getStartOdometer() != null && trip.getEndOdometer() != null) {
            trip.setDistanceKm(trip.getEndOdometer() - trip.getStartOdometer());
        } else {
            trip.setDistanceKm(null);
        }
    }

    private void requireStatus(FleetTrip trip, String action, FleetTripStatus... allowed) {
        for (FleetTripStatus s : allowed) {
            if (trip.getStatus() == s) return;
        }
        throw new BusinessException(
                "A " + trip.getStatus() + " trip cannot be " + action, HttpStatus.CONFLICT);
    }

    private FleetTripStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return FleetTripStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}