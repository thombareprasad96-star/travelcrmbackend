package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetSettlementStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One driver's hisaab for one trip — the printable settlement sheet, in base currency. */
@Getter
@Setter
public class FleetSettlementResponseDto {

    private UUID publicId;
    private UUID tripPublicId;
    private UUID driverPublicId;
    private String driverName;

    private FleetSettlementStatus status;
    private String statusLabel;

    private BigDecimal advanceTotal;
    private BigDecimal collectedTotal;
    private BigDecimal returnedTotal;
    private BigDecimal depositedTotal;
    private BigDecimal adjustmentTotal;
    private BigDecimal driverCashSpend;
    private BigDecimal allowanceTotal;

    /** &gt;0 the driver still holds company money; &lt;0 the company owes him; 0 squared. */
    private BigDecimal netDueFromDriver;
    private boolean squared;

    private LocalDateTime settledAt;
    private String settledBy;
    private LocalDateTime driverAcknowledgedAt;

    /**
     * A late bill or a reversal landed after this sheet was signed, so the frozen totals and the
     * driver's live balance no longer agree. Without surfacing it the trip reports green forever.
     */
    private boolean hasPostSettlementMovement;
}
