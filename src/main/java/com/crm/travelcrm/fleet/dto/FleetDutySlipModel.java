package com.crm.travelcrm.fleet.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything on a printed duty slip — the physical paper that rides with the vehicle.
 *
 * <p><b>This document is the product.</b> A duty slip leaves the office half-empty (vehicle,
 * driver, reporting time and place), the driver fills the readings by hand, the guest signs it at
 * release, and it comes back as the basis for billing and for the driver's settlement. Printing it
 * for a PLANNED trip with blanks is therefore not a degraded case — it is the primary case, and
 * why every field here tolerates a null instead of defaulting to a misleading zero.
 *
 * @param legs       one row per vehicle+driver span; a single-leg trip prints as one row, which is
 *                   what an unremarkable duty looks like
 * @param expenses   office-use block. Kept visually apart from the journey record because the guest
 *                   signs the journey, not the cost sheet
 */
@Getter
@Builder
public class FleetDutySlipModel {

    private final String slipNo;
    private final String jobReference;          // booking code, or free text in standalone
    private final String status;
    private final String statusLabel;

    private final String vehicleNumber;
    private final String vehicleType;
    private final String driverName;
    private final String driverPhone;
    private final String driverLicense;

    private final String routeFrom;
    private final String routeTo;
    private final String purpose;

    private final LocalDateTime startDatetime;
    private final LocalDateTime endDatetime;
    private final Integer startOdometer;
    private final Integer endOdometer;
    private final Integer distanceKm;

    private final List<Leg> legs;
    private final List<ExpenseLine> expenses;
    private final BigDecimal expenseTotal;

    private final String remarks;
    private final LocalDate generatedOn;

    @Getter
    @Builder
    public static class Leg {
        private final int seq;
        private final String vehicleNumber;
        private final String driverName;
        private final LocalDateTime startDatetime;
        private final LocalDateTime endDatetime;
        private final Integer startOdometer;
        private final Integer endOdometer;
        private final Integer distanceKm;
        private final String changeReason;
    }

    @Getter
    @Builder
    public static class ExpenseLine {
        private final LocalDate date;
        private final String type;
        private final String description;
        private final String paidBy;
        private final String driverName;
        private final BigDecimal amount;
        private final boolean hasReceipt;
    }
}
