package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDocumentStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
public class FleetDocumentResponseDto {

    private UUID publicId;

    private FleetRefType ownerType;
    private UUID vehiclePublicId;
    private String vehicleNumber;
    private UUID driverPublicId;
    private String driverName;

    private FleetDocumentCategory category;
    /** Display label, so the frontend keeps no copy of the enum vocabulary. */
    private String categoryLabel;

    private String documentNumber;
    private String issuingAuthority;
    private String countryCode;
    private String stateCode;
    private String borderPost;

    private LocalDate issuedOn;
    private LocalDate validFrom;
    private LocalDate validUntil;
    private LocalDate exitDeadline;

    /** Derived from the dates on every read — a stored status that disagrees with them is a lie. */
    private FleetDocumentStatus status;
    private String statusLabel;

    /** Negative once it has lapsed. Null when the document is open-ended. */
    private Long daysLeft;

    /** Days until the vehicle must be back across the border. Nepal entries only. */
    private Long exitDaysLeft;

    /** Whether an expired instance of this refuses an assignment, after any per-row override. */
    private boolean blocking;

    /** Set on the row this one replaced — the renewal chain. */
    private UUID supersedesPublicId;

    private UUID expensePublicId;

    /**
     * True on rows created by the legacy backfill, where only an expiry date was ever known. The
     * number, issue date and authority are genuinely unknown and were NOT invented.
     */
    private boolean needsReview;

    private String revokeReason;
    private String notes;
}
