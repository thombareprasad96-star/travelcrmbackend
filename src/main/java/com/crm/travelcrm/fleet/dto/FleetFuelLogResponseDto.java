package com.crm.travelcrm.fleet.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class FleetFuelLogResponseDto {

    private UUID publicId;
    private UUID vehiclePublicId;
    private String vehicleNumber;
    private LocalDate date;
    private BigDecimal liters;
    private BigDecimal cost;
    private Integer odometer;
    private String notes;
    private LocalDateTime createdAt;
}