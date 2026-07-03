package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
public class FleetTripResponseDto {

    private UUID publicId;

    private UUID vehiclePublicId;
    private String vehicleNumber;

    private UUID driverPublicId;
    private String driverName;

    private UUID bookingPublicId;
    private String bookingCode;

    private FleetTripStatus status;

    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private Integer startOdometer;
    private Integer endOdometer;
    private Integer distanceKm;

    private String routeFrom;
    private String routeTo;
    private String purpose;

    private BigDecimal fuelCost;
    private BigDecimal tollCost;
    private BigDecimal driverAllowance;

    /** Computed — sum of the non-null cost fields; null when none are recorded. */
    private BigDecimal totalExpense;

    private String remarks;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}