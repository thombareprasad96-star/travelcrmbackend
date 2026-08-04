package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetVehicle;

import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.mapper.FleetTripMapper;
import com.crm.travelcrm.fleet.repository.FleetComplianceDocumentRepository;
import com.crm.travelcrm.fleet.repository.FleetDocumentAlertRepository;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetFuelLogRepository;
import com.crm.travelcrm.fleet.repository.FleetMaintenanceLogRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FleetDashboardServiceImpl implements FleetDashboardService {

    private final FleetVehicleRepository vehicleRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetTripRepository tripRepository;
    private final FleetFuelLogRepository fuelLogRepository;
    private final FleetMaintenanceLogRepository maintenanceLogRepository;
    private final FleetComplianceDocumentRepository documentRepository;
    private final FleetDocumentAlertRepository alertRepository;
    private final FleetTripMapper tripMapper;

    /** How far ahead the dashboard looks for expiring documents. */
    @Value("${app.fleet.dashboard-expiry-window-days:30}")
    private int expiryWindowDays;

    @Value("${app.fleet.service-due-days:15}")
    private int serviceDueDays;

    @Value("${app.fleet.service-due-km-threshold:500}")
    private int serviceDueKmThreshold;

    /** How far ahead the dashboard looks for upcoming (planned) trips. */
    @Value("${app.fleet.dashboard-upcoming-window-days:7}")
    private int upcomingWindowDays;

    @Override
    @Transactional(readOnly = true)
    public FleetDashboardDto dashboard() {
        Long tenantId = FleetContext.tenantId();
        LocalDate today = LocalDate.now();

        // One group-by per entity instead of a count query per status value.
        Map<FleetVehicleStatus, Long> vehicleByStatus = countsByStatus(
                vehicleRepository.countGroupedByStatus(tenantId), FleetVehicleStatus.class);
        var vehicleCounts = new FleetDashboardDto.VehicleCounts(
                vehicleByStatus.values().stream().mapToLong(Long::longValue).sum(),
                vehicleByStatus.getOrDefault(FleetVehicleStatus.AVAILABLE, 0L),
                vehicleByStatus.getOrDefault(FleetVehicleStatus.ON_TRIP, 0L),
                vehicleByStatus.getOrDefault(FleetVehicleStatus.MAINTENANCE, 0L),
                vehicleByStatus.getOrDefault(FleetVehicleStatus.OUT_OF_SERVICE, 0L));

        Map<FleetDriverStatus, Long> driverByStatus = countsByStatus(
                driverRepository.countGroupedByStatus(tenantId), FleetDriverStatus.class);
        var driverCounts = new FleetDashboardDto.DriverCounts(
                driverByStatus.values().stream().mapToLong(Long::longValue).sum(),
                driverByStatus.getOrDefault(FleetDriverStatus.ACTIVE, 0L),
                tripRepository.countDistinctDriversByStatus(tenantId, FleetTripStatus.ONGOING));

        List<FleetTripResponseDto> ongoingTrips = tripRepository
                .findByTenantIdAndStatusAndDeletedAtIsNullOrderByStartDatetimeAsc(tenantId, FleetTripStatus.ONGOING)
                .stream().map(tripMapper::toDto).toList();

        LocalDateTime now = LocalDateTime.now();
        List<FleetTripResponseDto> upcomingTrips = tripRepository
                .findByTenantIdAndStatusAndStartDatetimeBetweenAndDeletedAtIsNullOrderByStartDatetimeAsc(
                        tenantId, FleetTripStatus.PLANNED, now, now.plusDays(upcomingWindowDays))
                .stream().map(tripMapper::toDto).toList();

        // Current-calendar-month window: [1st 00:00, 1st-of-next-month 00:00).
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);

        BigDecimal fuelSpend = fuelLogRepository.sumCostBetween(tenantId, monthStart, nextMonthStart);
        BigDecimal maintenanceSpend = maintenanceLogRepository.sumCostBetween(tenantId, monthStart, nextMonthStart);
        var monthlySpend = new FleetDashboardDto.MonthlySpend(
                fuelSpend, maintenanceSpend, fuelSpend.add(maintenanceSpend));

        List<Object[]> tripStatsRows = tripRepository.countAndDistanceByStatusAndEndBetween(
                tenantId, FleetTripStatus.COMPLETED, monthStart.atStartOfDay(), nextMonthStart.atStartOfDay());
        Object[] tripStatsRow = tripStatsRows.isEmpty() ? new Object[]{0L, 0L} : tripStatsRows.get(0);
        var tripsThisMonth = new FleetDashboardDto.TripStats(
                ((Number) tripStatsRow[0]).longValue(), ((Number) tripStatsRow[1]).longValue());

        List<FleetServiceDueDto> serviceDue = vehicleRepository
                .findServiceDue(tenantId, today.plusDays(serviceDueDays), serviceDueKmThreshold)
                .stream()
                .map(v -> new FleetServiceDueDto(
                        v.getPublicId(), v.getVehicleNumber(),
                        v.getNextServiceDueDate(), v.getNextServiceDueKm(), v.getLastOdometer(),
                        (v.getNextServiceDueKm() != null && v.getLastOdometer() != null)
                                ? v.getNextServiceDueKm() - v.getLastOdometer() : null))
                .toList();

        return FleetDashboardDto.builder()
                .vehicles(vehicleCounts)
                .drivers(driverCounts)
                .monthlySpend(monthlySpend)
                .tripsThisMonth(tripsThisMonth)
                .ongoingTrips(ongoingTrips)
                .upcomingTrips(upcomingTrips)
                .expiringDocuments(collectExpiringDocuments(tenantId, today))
                .serviceDue(serviceDue)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetDocumentAlertDto> alerts(int page, int size) {
        Long tenantId = FleetContext.tenantId();
        Page<com.crm.travelcrm.fleet.entity.FleetDocumentAlert> result = alertRepository
                .findByTenantIdAndDeletedAtIsNull(tenantId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<FleetDocumentAlertDto> data = result
                .map(a -> new FleetDocumentAlertDto(a.getPublicId(), a.getRefType(), a.getRefPublicId(),
                        a.getRefLabel(), a.getDocType(), a.getDocType().label(), a.getExpiryDate(),
                        a.getDaysLeft(), a.getThresholdDays(), a.getCreatedAt()))
                .getContent();
        return PagedApiResponse.of("Alerts fetched successfully", data, PaginationMeta.from(result));
    }

    /**
     * Expiring papers, read from the compliance table.
     *
     * <p>This used to explode the vehicle's four date columns and the driver's licence date. Those
     * columns were migrated into document rows, so reading documents covers everything the old code
     * covered plus the fourteen categories it had no room for — fitness, Green Card, VLTD, PSV badge
     * and the rest. Keeping the old reads alongside would list insurance twice.
     */
    private List<FleetExpiringDocumentDto> collectExpiringDocuments(Long tenantId, LocalDate today) {
        LocalDate limit = today.plusDays(expiryWindowDays);
        List<FleetExpiringDocumentDto> items = new ArrayList<>();

        for (var doc : documentRepository.findExpiringBy(tenantId, limit)) {
            boolean isVehicle = doc.getVehicle() != null;
            items.add(new FleetExpiringDocumentDto(
                    isVehicle ? FleetRefType.VEHICLE : FleetRefType.DRIVER,
                    isVehicle ? doc.getVehicle().getPublicId() : doc.getDriver().getPublicId(),
                    isVehicle ? doc.getVehicle().getVehicleNumber() : doc.getDriver().getName(),
                    doc.getCategory(), doc.getCategory().label(),
                    doc.getValidUntil(),
                    ChronoUnit.DAYS.between(today, doc.getValidUntil())));
        }

        items.sort(Comparator.comparingLong(FleetExpiringDocumentDto::daysLeft));
        return items;
    }

    /** {@code [status, count]} group-by rows → typed map. */
    private <E extends Enum<E>> Map<E, Long> countsByStatus(List<Object[]> rows, Class<E> type) {
        Map<E, Long> counts = new EnumMap<>(type);
        for (Object[] row : rows) {
            counts.put(type.cast(row[0]), (Long) row[1]);
        }
        return counts;
    }
}