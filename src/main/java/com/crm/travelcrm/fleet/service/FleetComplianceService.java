package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetDocumentRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDocumentResponseDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FleetComplianceService {

    FleetDocumentResponseDto create(FleetDocumentRequestDto request);

    /** Edit a CURRENT document. A superseded or revoked row is history and cannot be changed. */
    FleetDocumentResponseDto update(UUID publicId, FleetDocumentRequestDto request);

    /**
     * Insert the replacement and mark the original superseded — never overwrite. The old number,
     * authority, validity and cost are what answers "what was valid on this past date".
     */
    FleetDocumentResponseDto renew(UUID publicId, FleetDocumentRequestDto request);

    FleetDocumentResponseDto revoke(UUID publicId, String reason);

    void delete(UUID publicId);

    /** Full history for one asset, renewal chain included. */
    List<FleetDocumentResponseDto> forVehicle(UUID vehiclePublicId);

    List<FleetDocumentResponseDto> forDriver(UUID driverPublicId);

    List<FleetDocumentResponseDto> expiring(Integer withinDays);

    /**
     * Filtered, paginated list. The only way to reach a document that is neither expiring nor
     * attached to an asset you already have open — including the backfilled rows that still need a
     * human to fill in their number and authority.
     */
    PagedApiResponse<FleetDocumentResponseDto> list(
            String ownerType, UUID vehicleId, UUID driverId, String category,
            String status, Boolean needsReview, String search, int page, int size);

    /**
     * Is this vehicle/driver legal for a duty running through {@code throughDate}?
     *
     * <p>Checked against the trip's RETURN date, not today — a permit that lapses on day six of an
     * eleven-day run passes every "valid now" test right up until the barrier.
     */
    ComplianceCheck check(UUID vehiclePublicId, UUID driverPublicId, LocalDate throughDate);

    /**
     * @param clear    true when nothing blocks; warnings may still be present
     * @param through  the date checked against
     * @param blockers papers that refuse the assignment (owner may override with a reason)
     * @param warnings papers that have lapsed but only warn
     */
    record ComplianceCheck(
            boolean clear,
            LocalDate through,
            List<FleetDocumentResponseDto> blockers,
            List<FleetDocumentResponseDto> warnings
    ) {
    }
}
