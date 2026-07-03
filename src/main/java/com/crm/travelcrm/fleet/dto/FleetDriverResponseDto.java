package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class FleetDriverResponseDto {

    private UUID publicId;
    private String name;
    private String phone;
    private String licenseNumber;
    private LocalDate licenseExpiry;
    private FleetDriverStatus status;

    /** Computed — true while the driver has an ONGOING trip (set by the service, not stored). */
    private boolean onTrip;

    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}