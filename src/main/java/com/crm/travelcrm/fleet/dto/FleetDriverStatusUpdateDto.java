package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** PATCH /api/fleet/drivers/{publicId}/status body. */
@Getter
@Setter
public class FleetDriverStatusUpdateDto {

    @NotNull
    private FleetDriverStatus status;
}