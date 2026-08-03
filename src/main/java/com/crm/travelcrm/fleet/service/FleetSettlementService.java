package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.fleet.dto.FleetCashDirectionDto;
import com.crm.travelcrm.fleet.dto.FleetCashEntryRequestDto;
import com.crm.travelcrm.fleet.dto.FleetSettlementResponseDto;

import java.util.List;
import java.util.UUID;

public interface FleetSettlementService {

    /** The movement catalogue + per-direction form metadata, so the frontend keeps no copy of the enum. */
    List<FleetCashDirectionDto> cashDirections();

    /**
     * Records an advance, return, collection, deposit, recovery or adjustment.
     * Returns the recomputed settlement, or null when the movement is not tied to a trip.
     */
    FleetSettlementResponseDto recordCash(FleetCashEntryRequestDto request);

    /** Recompute and mark the sheet reconciled — printable, not yet signed. */
    FleetSettlementResponseDto reconcile(UUID tripPublicId, UUID driverPublicId);

    /** Sign off. Refuses unless the driver's cash is squared to exactly zero and he has acknowledged. */
    FleetSettlementResponseDto settle(UUID tripPublicId, UUID driverPublicId, boolean driverAcknowledged);

    /** Every driver's sheet for one trip — one per man on a multi-driver trip. */
    List<FleetSettlementResponseDto> forTrip(UUID tripPublicId);

    /** The unsquared worklist: whose cash is still out. */
    List<FleetSettlementResponseDto> openSettlements();

    /**
     * The printable hisaab for one driver on one trip — the paper he actually signs.
     *
     * <p>Printable before signing too, marked DRAFT: an accountant checks the sheet against the
     * cash box before anyone puts a pen to it. The totals are the settlement's stored figures; the
     * itemised lines are supporting evidence, never a second computation of the same numbers.
     */
    byte[] settlementSheet(UUID tripPublicId, UUID driverPublicId);
}
