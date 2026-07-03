package com.crm.travelcrm.fleet.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class FleetMaintenanceLogResponseDto {

    private UUID publicId;
    private UUID vehiclePublicId;
    private String vehicleNumber;
    private LocalDate serviceDate;
    private String serviceType;
    private BigDecimal cost;
    private String vendorName;
    private Integer odometer;
    private LocalDate nextServiceDueDate;
    private Integer nextServiceDueKm;
    private String notes;
    private LocalDateTime createdAt;
}