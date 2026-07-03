package com.crm.travelcrm.fleet.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Vehicle nearing its next service — by due date, by due km, or both. */
public record FleetServiceDueDto(
        UUID vehiclePublicId,
        String vehicleNumber,
        LocalDate nextServiceDueDate,
        Integer nextServiceDueKm,
        Integer lastOdometer,
        Integer kmRemaining
) {
}