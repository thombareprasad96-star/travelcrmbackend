package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** PATCH /api/fleet/vehicles/{publicId}/status body. ON_TRIP is rejected — trips manage it. */
@Getter
@Setter
public class FleetVehicleStatusUpdateDto {

    @NotNull
    private FleetVehicleStatus status;
}