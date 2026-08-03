package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetExpenseType;
import com.crm.travelcrm.fleet.enums.FleetPaidBy;
import com.crm.travelcrm.fleet.enums.FleetTaxCharacter;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
public class FleetExpenseResponseDto {

    private UUID publicId;

    private UUID vehiclePublicId;
    private String vehicleNumber;

    private UUID tripPublicId;
    private String tripRoute;

    private UUID driverPublicId;
    private String driverName;

    private FleetExpenseType expenseType;
    /** Display label, so the frontend never keeps its own copy of the enum vocabulary. */
    private String expenseTypeLabel;

    private LocalDate documentDate;
    private LocalTime documentTime;
    private LocalDate postingDate;

    private BigDecimal amount;
    private String currency;
    private BigDecimal fxRate;
    /** In INR. The ONLY figure a report may sum — never re-convert an aggregate. */
    private BigDecimal baseAmount;

    private FleetPaidBy paidBy;
    private String paidByLabel;

    private boolean hasReceipt;
    private String noReceiptReason;
    private String referenceNumber;

    private String supplierGstin;
    private BigDecimal taxableValue;
    private BigDecimal gstRate;
    private BigDecimal gstAmount;
    private boolean itcEligible;
    private FleetTaxCharacter taxCharacter;

    /** Set when this row cancels another — the UI shows it struck through beside its original. */
    private UUID reversalOfPublicId;
    private String reversalReason;
    /** True when THIS row has already been reversed by a later one, so the UI hides its Reverse action. */
    private boolean reversed;

    /** False once the trip is settled or the period is closed — drives edit/delete affordances. */
    private boolean editable;

    private String description;
    private String notes;
    private LocalDateTime createdAt;
    private String enteredBy;
}
