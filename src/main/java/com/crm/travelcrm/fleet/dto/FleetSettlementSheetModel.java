package com.crm.travelcrm.fleet.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The driver's hisaab, printed — one trip, one driver, one signature.
 *
 * <p><b>What it is for.</b> The settlement screen shows the number; this is the paper the driver
 * actually signs. The office prints it, the driver reads the lines, signs, and a photo of the
 * signed sheet is attached back onto the settlement — which is how "I never agreed to that"
 * six months later stops being an argument.
 *
 * <p><b>The totals are the SETTLEMENT's stored figures, not re-derived here.</b> The itemised
 * lines are supporting evidence. A sheet that recomputes its own totals is a second source of
 * truth, and the first thing it will do is disagree with the screen the driver was shown.
 */
@Getter
@Builder
public class FleetSettlementSheetModel {

    private final String sheetNo;
    private final String driverName;
    private final String driverPhone;
    private final String vehicleNumber;
    private final String jobReference;
    private final String routeFrom;
    private final String routeTo;
    private final LocalDateTime tripStart;
    private final LocalDateTime tripEnd;

    private final String status;
    private final String statusLabel;

    // Frozen totals, straight off the settlement row.
    private final BigDecimal advanceTotal;
    private final BigDecimal collectedTotal;
    private final BigDecimal returnedTotal;
    private final BigDecimal depositedTotal;
    private final BigDecimal adjustmentTotal;
    private final BigDecimal driverCashSpend;
    private final BigDecimal allowanceTotal;
    private final BigDecimal netDueFromDriver;
    private final boolean squared;

    /** True while the sheet is still open — printed as a DRAFT watermark so it is not signed early. */
    private final boolean draft;

    /**
     * A late bill or a reversal landed after this sheet was signed, so the frozen totals above and
     * the itemised lines below no longer describe the same set of rows.
     *
     * <p>Printed as a warning band rather than silently reconciled: the totals are what the driver
     * agreed to and must not change, but a reader comparing them against the lines deserves to know
     * why they do not tally.
     */
    private final boolean postSettlementMovement;
    private final LocalDateTime settledAt;
    private final String settledBy;

    private final List<CashLine> cashLines;
    private final List<SpendLine> spendLines;

    private final String notes;
    private final LocalDate generatedOn;

    /** One movement on the driver's imprest account. */
    @Getter
    @Builder
    public static class CashLine {
        private final LocalDate date;
        private final String direction;
        /** +1 increases what the driver owes, −1 discharges it — printed as a word, not a sign. */
        private final int signum;
        private final BigDecimal amount;
        private final String reason;
        private final String reference;
        private final String partyReference;
    }

    /** One expense paid out of the driver's own cash — what discharges the advance. */
    @Getter
    @Builder
    public static class SpendLine {
        private final LocalDate date;
        private final String type;
        private final String description;
        private final BigDecimal amount;
        private final boolean hasReceipt;
    }
}
