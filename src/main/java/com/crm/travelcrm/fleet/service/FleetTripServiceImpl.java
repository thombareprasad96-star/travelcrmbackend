package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetLegChangeReason;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReference;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReferencePort;
import com.crm.travelcrm.fleet.mapper.FleetTripMapper;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetExpenseRepository;
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

import java.math.BigDecimal;
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
    private final FleetJobReferencePort jobReferencePort;
    private final FleetExpenseRepository expenseRepository;
    private final FleetTripLegManager legManager;
    private final FleetTripMapper mapper;
    private final FleetPdfService pdfService;
    // "now" defaults for start/close/handover are the TENANT's now, not the server's: an ONGOING
    // trip started at 23:50 NPT must not land on the previous IST day in every dated report.
    private final TenantTimeZone tenantTimeZone;

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
        // Every trip is a sequence of legs, starting with one. Without this the trip has no leg at
        // all, and two things fail silently downstream: an expense cannot resolve which driver it
        // belongs to, and the settlement computes the allowance from days in a driver's own legs —
        // so a legless trip pays a bata of exactly zero.
        legManager.openFirstLeg(saved);

        log.info("Fleet trip created | id: {} | status: {} | vehicle: {} | tenantId: {}",
                saved.getId(), saved.getStatus(), vehicle.getVehicleNumber(), tenantId);
        return toDtoWithLedgerCost(saved);
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

        LocalDateTime startAt = request.getStartDatetime() != null
                ? request.getStartDatetime() : tenantTimeZone.now();
        trip.setStartOdometer(request.getStartOdometer());
        trip.setStartDatetime(startAt);
        trip.setStatus(FleetTripStatus.ONGOING);
        vehicle.setStatus(FleetVehicleStatus.ON_TRIP);
        vehicle.bumpOdometer(request.getStartOdometer());

        FleetTrip saved = tripRepository.save(trip);
        legManager.applyStart(saved, request.getStartOdometer(), startAt);
        log.info("Fleet trip started | id: {} | vehicle: {} | odometer: {}",
                saved.getId(), vehicle.getVehicleNumber(), request.getStartOdometer());
        return toDtoWithLedgerCost(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto close(UUID publicId, FleetTripCloseDto request) {
        FleetTrip trip = findOrThrow(publicId);
        requireStatus(trip, "closed", FleetTripStatus.ONGOING);

        LocalDateTime end = request.getEndDatetime() != null ? request.getEndDatetime() : tenantTimeZone.now();

        // Floor is the CURRENT LEG's start reading, not the trip's. On a substituted trip the
        // trip-level start belongs to a different vehicle entirely: validating against it would
        // reject a perfectly good reading when the replacement has a lower odometer, and the trip
        // could then never reach COMPLETED — which also means its settlement never opens and the
        // driver's cash is stuck for good.
        Integer floor = legManager.closeFloor(trip);
        if (floor != null && request.getEndOdometer() < floor) {
            throw new BusinessException(
                    "End odometer cannot be less than this vehicle's start reading (" + floor + ")",
                    HttpStatus.BAD_REQUEST);
        }
        if (!end.isAfter(trip.getStartDatetime())) {
            throw new BusinessException("End time must be after the start time", HttpStatus.BAD_REQUEST);
        }

        trip.setEndOdometer(request.getEndOdometer());
        trip.setEndDatetime(end);
        // OLD — removed in the ledger cutover:
        //   if (request.getFuelCost() != null) trip.setFuelCost(request.getFuelCost());
        //   if (request.getTollCost() != null) trip.setTollCost(request.getTollCost());
        //   if (request.getDriverAllowance() != null) trip.setDriverAllowance(request.getDriverAllowance());
        // Closing a trip no longer records cost. Fuel, tolls and the driver's allowance all belong to
        // the expense ledger, which is where the driver's cash settlement and the vehicle's running
        // cost read from; writing them here as well counted the same money twice.
        if (StringUtils.hasText(request.getRemarks())) trip.setRemarks(request.getRemarks());
        trip.setStatus(FleetTripStatus.COMPLETED);

        // Distance is the SUM OF LEGS, never end − start. With one leg the two are arithmetically
        // identical, which is what keeps every existing trip's number unchanged; with two they are
        // not — 45,000→45,200 then 88,000→88,300 is 500 km, and the subtraction says 43,300.
        trip.setDistanceKm(legManager.applyClose(trip, request.getEndOdometer(), end));

        FleetVehicle vehicle = trip.getVehicle();
        if (vehicle.getStatus() == FleetVehicleStatus.ON_TRIP) {
            vehicle.setStatus(FleetVehicleStatus.AVAILABLE);
        }
        vehicle.bumpOdometer(request.getEndOdometer());

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip closed | id: {} | distanceKm: {}", saved.getId(), saved.getDistanceKm());
        return toDtoWithLedgerCost(saved);
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
        return toDtoWithLedgerCost(saved);
    }

    @Override
    @Transactional
    public FleetTripResponseDto swap(UUID publicId, FleetTripSwapDto request) {
        FleetTrip trip = findOrThrow(publicId);
        requireStatus(trip, "handed over", FleetTripStatus.ONGOING);
        Long tenantId = trip.getTenantId();

        FleetVehicle outgoing = trip.getVehicle();
        FleetDriver outgoingDriver = trip.getDriver();

        FleetVehicle incoming = request.getVehiclePublicId() != null
                ? resolveVehicle(request.getVehiclePublicId(), tenantId) : outgoing;
        FleetDriver incomingDriver = request.getDriverPublicId() != null
                ? resolveActiveDriver(request.getDriverPublicId(), tenantId) : outgoingDriver;

        if (incoming.getId() == outgoing.getId() && incomingDriver.getId() == outgoingDriver.getId()) {
            throw new BusinessException(
                    "Nothing changed — pick a different vehicle or driver", HttpStatus.BAD_REQUEST);
        }

        // Only check the incoming vehicle when it is genuinely a different one: on a pure driver
        // handover the current vehicle is legitimately ON_TRIP — this trip is what put it there.
        if (incoming.getId() != outgoing.getId()) {
            if (incoming.isDeleted()) {
                throw new BusinessException("That vehicle is in Trash", HttpStatus.CONFLICT);
            }
            if (incoming.getStatus() != FleetVehicleStatus.AVAILABLE) {
                throw new BusinessException(
                        incoming.getVehicleNumber() + " is " + incoming.getStatus()
                                + " — not available to take over", HttpStatus.CONFLICT);
            }
        }
        if (incomingDriver.getId() != outgoingDriver.getId()
                && tripRepository.existsByDriver_IdAndStatusAndDeletedAtIsNull(
                        incomingDriver.getId(), FleetTripStatus.ONGOING)) {
            throw new BusinessException(
                    incomingDriver.getName() + " is already on an ongoing trip", HttpStatus.CONFLICT);
        }

        LocalDateTime at = request.getAt() != null ? request.getAt() : tenantTimeZone.now();
        legManager.swap(trip, incoming, incomingDriver, request.getChangeReason(),
                request.getAtOdometer(), request.getNewStartOdometer(), at);

        // The trip's vehicle/driver are a pointer to the CURRENT leg, which is now the new one.
        // Existing list views and specifications keep working unchanged because of that.
        trip.setVehicle(incoming);
        trip.setDriver(incomingDriver);
        trip.setDistanceKm(legManager.totalDistance(trip));

        if (incoming.getId() != outgoing.getId()) {
            outgoing.setStatus(FleetVehicleStatus.AVAILABLE);
            outgoing.bumpOdometer(request.getAtOdometer());
            incoming.setStatus(FleetVehicleStatus.ON_TRIP);
            incoming.bumpOdometer(request.getNewStartOdometer());
        }

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip handed over | id: {} | {} → {} | driver {} → {} | reason: {}",
                saved.getId(), outgoing.getVehicleNumber(), incoming.getVehicleNumber(),
                outgoingDriver.getName(), incomingDriver.getName(), request.getChangeReason());
        return toDtoWithLedgerCost(saved);
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

        // A PLANNED reassignment is a correction, not a substitution — nothing has happened yet, so
        // leg 1 is realigned rather than a second leg opened. syncPlannedAssignment refuses to touch
        // a trip that already has more than one leg, so a real handover is never rewritten.
        legManager.syncPlannedAssignment(trip);
        // Distance still comes from the legs; on a single-leg trip this equals end − start exactly.
        trip.setDistanceKm(legManager.totalDistance(trip));

        // Only a finished trip's reading is real — a planned end odometer must not
        // pollute the vehicle's bump-up-only lastOdometer.
        if (trip.getStatus() == FleetTripStatus.COMPLETED) {
            trip.getVehicle().bumpOdometer(trip.getEndOdometer());
        }

        FleetTrip saved = tripRepository.save(trip);
        log.info("Fleet trip updated | id: {} | tenantId: {}", saved.getId(), tenantId);
        return toDtoWithLedgerCost(saved);
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
        return toDtoWithLedgerCost(findOrThrow(publicId));
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
        List<FleetTripResponseDto> data = result.map(this::toDtoWithLedgerCost).getContent();
        return PagedApiResponse.of("Trips fetched successfully", data, PaginationMeta.from(result));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<FleetTripLegDto> legs(UUID publicId) {
        FleetTrip trip = findOrThrow(publicId);
        return legManager.legsOf(trip).stream()
                .map(l -> new FleetTripLegDto(
                        l.getPublicId(), l.getSeq(),
                        l.getVehicle().getPublicId(), l.getVehicle().getVehicleNumber(),
                        l.getDriver().getPublicId(), l.getDriver().getName(),
                        l.getStartDatetime(), l.getEndDatetime(),
                        l.getStartOdometer(), l.getEndOdometer(), l.getDistanceKm(),
                        l.getChangeReason(),
                        l.getChangeReason() == null ? null : l.getChangeReason().label(),
                        l.getNotes()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] dutySlip(UUID publicId) {
        FleetTrip trip = findOrThrow(publicId);
        Long tenantId = trip.getTenantId();

        List<FleetDutySlipModel.Leg> legs = legManager.legsOf(trip).stream()
                .map(l -> FleetDutySlipModel.Leg.builder()
                        .seq(l.getSeq())
                        .vehicleNumber(l.getVehicle().getVehicleNumber())
                        .driverName(l.getDriver().getName())
                        .startDatetime(l.getStartDatetime())
                        .endDatetime(l.getEndDatetime())
                        .startOdometer(l.getStartOdometer())
                        .endOdometer(l.getEndOdometer())
                        .distanceKm(l.getDistanceKm())
                        .changeReason(l.getChangeReason() == null ? null : l.getChangeReason().label())
                        .build())
                .toList();

        // The slip itself rides FLEET_READ so a dispatcher can print it — but the cost block on it
        // is money, and the money grant is separate by design. Gating only the ROUTE would hand
        // every dispatcher the vehicle's cost structure on a sheet of paper.
        boolean money = FleetContext.canSeeMoney();
        List<FleetDutySlipModel.ExpenseLine> expenses = money
                ? expenseRepository
                        .findByTenantIdAndTrip_IdAndDeletedAtIsNullOrderByDocumentDateAscIdAsc(tenantId, trip.getId())
                        .stream()
                        .map(e -> FleetDutySlipModel.ExpenseLine.builder()
                                .date(e.getDocumentDate())
                                .type(e.getExpenseType().label())
                                .description(e.getDescription())
                                .paidBy(e.getPaidBy().label())
                                .driverName(e.getDriver() == null ? null : e.getDriver().getName())
                                .amount(e.getBaseAmount())
                                .hasReceipt(e.isHasReceipt())
                                .build())
                        .toList()
                : List.of();

        FleetDutySlipModel model = FleetDutySlipModel.builder()
                .slipNo(slipNo("DS", trip.getPublicId()))
                .jobReference(trip.getBookingCode())
                .status(trip.getStatus().name())
                .statusLabel(statusLabel(trip.getStatus()))
                .vehicleNumber(trip.getVehicle().getVehicleNumber())
                .vehicleType(trip.getVehicle().getType())
                .driverName(trip.getDriver().getName())
                .driverPhone(trip.getDriver().getPhone())
                .driverLicense(trip.getDriver().getLicenseNumber())
                .routeFrom(trip.getRouteFrom())
                .routeTo(trip.getRouteTo())
                .purpose(trip.getPurpose())
                .startDatetime(trip.getStartDatetime())
                .endDatetime(trip.getEndDatetime())
                .startOdometer(trip.getStartOdometer())
                .endOdometer(trip.getEndOdometer())
                .distanceKm(trip.getDistanceKm())
                .legs(legs)
                .expenses(expenses)
                // The canonical aggregate, not a sum over the printed lines: reversals net by
                // arithmetic there, and the slip must agree with every other screen showing this trip.
                .expenseTotal(money ? expenseRepository.sumTripCost(tenantId, trip.getId()) : null)
                .remarks(trip.getRemarks())
                .generatedOn(tenantTimeZone.today())
                .build();

        return pdfService.renderDutySlip(model);
    }

    /** Human-quotable document reference. A trip has no code of its own, so its publicId is it. */
    static String slipNo(String prefix, UUID publicId) {
        return prefix + "-" + publicId.toString().substring(0, 8).toUpperCase();
    }

    private static String statusLabel(FleetTripStatus status) {
        return switch (status) {
            case PLANNED -> "Planned";
            case ONGOING -> "In Progress";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    @Override
    public List<FleetLegChangeReasonDto> legChangeReasons() {
        return java.util.Arrays.stream(FleetLegChangeReason.values())
                .map(r -> new FleetLegChangeReasonDto(r.name(), r.label()))
                .toList();
    }

    private FleetTrip findOrThrow(UUID publicId) {
        return tripRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, FleetContext.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found: " + publicId));
    }

    /**
     * Maps a trip and replaces its legacy scalar total with the LEDGER total.
     *
     * <p>Two sources for one trip's cost is how a screen shows Rs 6,000 while the expense list under
     * it shows Rs 12,000. The ledger is the source of truth: it carries currency, payer, receipts and
     * the driver's cash position, and it is what the settlement reads.
     *
     * <p>A trip that predates the ledger keeps showing its old figure — {@code sumTripCost} returns
     * zero for it, and falling back to the mapper's scalar total is better than replacing a real
     * historical number with a zero. Once the backfill has run and reconciled, this fallback and the
     * three columns go together.
     */
    private FleetTripResponseDto toDtoWithLedgerCost(FleetTrip trip) {
        FleetTripResponseDto dto = mapper.toDto(trip);
        BigDecimal ledger = expenseRepository.sumTripCost(trip.getTenantId(), trip.getId());
        if (ledger != null && ledger.compareTo(BigDecimal.ZERO) != 0) {
            dto.setTotalExpense(ledger);
        }
        return dto;
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

    /**
     * Resolves + tenant-validates the job link and snapshots id/publicId/code for display.
     *
     * <p>Goes through {@link FleetJobReferencePort} rather than importing {@code Booking}, so the
     * same code compiles and runs in a Fleet-only deployment that has no booking module at all. In
     * CRM mode the adapter performs exactly the tenant-scoped, soft-delete-aware lookup this method
     * used to do inline — behaviour is unchanged.
     */
    private void applyBooking(FleetTrip trip, UUID bookingPublicId, Long tenantId) {
        if (bookingPublicId == null) return;
        FleetJobReference job = jobReferencePort.resolve(bookingPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));
        trip.setBookingId(job.id());
        trip.setBookingPublicId(job.publicId());
        trip.setBookingCode(job.code());
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