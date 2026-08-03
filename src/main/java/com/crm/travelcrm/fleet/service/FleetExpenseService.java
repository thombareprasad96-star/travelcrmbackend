package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetExpenseRequestDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseResponseDto;
import com.crm.travelcrm.fleet.dto.FleetExpenseTypeDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FleetExpenseService {

    /** The category catalogue + per-type form metadata, so the frontend keeps no copy of the enum. */
    List<FleetExpenseTypeDto> expenseTypes();

    FleetExpenseResponseDto create(FleetExpenseRequestDto request);

    /** In-place edit — allowed only while the trip is unsettled AND the period is open. */
    FleetExpenseResponseDto update(UUID publicId, FleetExpenseRequestDto request);

    /**
     * Cancels a row that can no longer be edited, by writing a NEW opposing row dated with the
     * original's document date and today's posting date. Never a status flip.
     */
    FleetExpenseResponseDto reverse(UUID publicId, String reason);

    /** Soft-delete, permitted only while the row is still editable. Otherwise use {@link #reverse}. */
    void delete(UUID publicId);

    FleetExpenseResponseDto getByPublicId(UUID publicId);

    /**
     * True while this row's money can still change — its settlement unsigned AND its period open.
     * THE freeze definition, shared with the attachment service so evidence deletability and row
     * editability can never disagree.
     */
    boolean isEditable(com.crm.travelcrm.fleet.entity.FleetExpense expense);

    PagedApiResponse<FleetExpenseResponseDto> list(
            UUID vehiclePublicId, UUID tripPublicId, UUID driverPublicId,
            String type, String paidBy, LocalDate fromDate, LocalDate toDate,
            Boolean missingReceipt, String search, int page, int size);
}
