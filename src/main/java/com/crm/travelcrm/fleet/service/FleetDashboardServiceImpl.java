package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetDocumentType;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import com.crm.travelcrm.fleet.mapper.FleetTripMapper;
import com.crm.travelcrm.fleet.repository.FleetDocumentAlertRepository;
import com.crm.travelcrm.fleet.repository.FleetDriverRepository;
import com.crm.travelcrm.fleet.repository.FleetTripRepository;
import com.crm.travelcrm.fleet.repository.FleetVehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
    private final FleetDocumentAlertRepository alertRepository;
    private final FleetTripMapper tripMapper;

    /** How far ahead the dashboard looks for expiring documents. */
    @Value("${app.fleet.dashboard-expiry-window-days:30}")
    private int expiryWindowDays;

    @Value("${app.fleet.service-due-days:15}")
    private int serviceDueDays;

    @Value("${app.fleet.service-due-km-threshold:500}")
    private int serviceDueKmThreshold;

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
                .ongoingTrips(ongoingTrips)
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

    /** Explodes each vehicle's four document dates + active drivers' licences into dashboard rows. */
    private List<FleetExpiringDocumentDto> collectExpiringDocuments(Long tenantId, LocalDate today) {
        LocalDate limit = today.plusDays(expiryWindowDays);
        List<FleetExpiringDocumentDto> items = new ArrayList<>();

        for (FleetVehicle v : vehicleRepository.findWithDocumentsExpiringBy(tenantId, limit)) {
            addIfExpiring(items, today, limit, FleetRefType.VEHICLE, v.getPublicId(), v.getVehicleNumber(),
                    FleetDocumentType.INSURANCE, v.getInsuranceExpiry());
            addIfExpiring(items, today, limit, FleetRefType.VEHICLE, v.getPublicId(), v.getVehicleNumber(),
                    FleetDocumentType.RC, v.getRcExpiry());
            addIfExpiring(items, today, limit, FleetRefType.VEHICLE, v.getPublicId(), v.getVehicleNumber(),
                    FleetDocumentType.PERMIT, v.getPermitExpiry());
            addIfExpiring(items, today, limit, FleetRefType.VEHICLE, v.getPublicId(), v.getVehicleNumber(),
                    FleetDocumentType.PUC, v.getPucExpiry());
        }
        for (FleetDriver d : driverRepository
                .findByTenantIdAndStatusAndLicenseExpiryLessThanEqualAndDeletedAtIsNull(
                        tenantId, FleetDriverStatus.ACTIVE, limit)) {
            addIfExpiring(items, today, limit, FleetRefType.DRIVER, d.getPublicId(), d.getName(),
                    FleetDocumentType.DRIVER_LICENSE, d.getLicenseExpiry());
        }

        items.sort(Comparator.comparingLong(FleetExpiringDocumentDto::daysLeft));
        return items;
    }

    private void addIfExpiring(List<FleetExpiringDocumentDto> items, LocalDate today, LocalDate limit,
                               FleetRefType refType, java.util.UUID refPublicId, String refLabel,
                               FleetDocumentType docType, LocalDate expiry) {
        if (expiry == null || expiry.isAfter(limit)) return;
        items.add(new FleetExpiringDocumentDto(refType, refPublicId, refLabel, docType, docType.label(),
                expiry, ChronoUnit.DAYS.between(today, expiry)));
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