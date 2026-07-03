package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Vehicle API shape — exposes {@code publicId} only, never the internal Long id. */
@Getter
@Setter
public class FleetVehicleResponseDto {

    private UUID publicId;
    private String vehicleNumber;
    private String type;
    private String make;
    private String model;
    private Integer year;
    private Integer seatingCapacity;
    private FleetOwnerType ownerType;

    private UUID vendorPublicId;
    private String vendorName;

    private LocalDate insuranceExpiry;
    private LocalDate rcExpiry;
    private LocalDate permitExpiry;
    private LocalDate pucExpiry;

    private FleetVehicleStatus status;
    private Integer lastOdometer;
    private LocalDate nextServiceDueDate;
    private Integer nextServiceDueKm;
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}