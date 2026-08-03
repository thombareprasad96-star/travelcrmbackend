package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * POST /api/fleet/documents, and the body of a renewal.
 *
 * <p>Exactly one of {@link #vehiclePublicId} / {@link #driverPublicId} — validated in the service
 * and enforced by a CHECK constraint. A polymorphic owner with two nullable columns is only safe
 * while something actually says so.
 */
@Getter
@Setter
public class FleetDocumentRequestDto {

    private UUID vehiclePublicId;
    private UUID driverPublicId;

    @NotNull(message = "Which document is this?")
    private FleetDocumentCategory category;

    @Size(max = 60)
    private String documentNumber;

    @Size(max = 150)
    private String issuingAuthority;

    /** Defaults from the category (Nepal papers are NP) when omitted. */
    @Size(max = 2)
    private String countryCode;

    /** Required for a state permit / road tax / green tax — a permit for UP is not one for Rajasthan. */
    @Size(max = 40)
    private String stateCode;

    @Size(max = 80)
    private String borderPost;

    private LocalDate issuedOn;
    private LocalDate validFrom;

    /** Null means open-ended — a lifetime registration. Everything else drives the expiry alerts. */
    private LocalDate validUntil;

    /**
     * Nepal entry only: the date the vehicle must be back across the border. Deliberately separate
     * from validity — the paper can be valid for a month while the stay is capped at seven days, and
     * overstaying is a fine at the border rather than an invalid document.
     */
    private LocalDate exitDeadline;

    /**
     * Overrides the category's default. Null = use the default. Exists because a dispatcher wants
     * compliance to warn and never block, while an accountant wants an expired PSV badge to refuse
     * the assignment — both correct, about different papers.
     */
    private Boolean blocking;

    /** The expense that paid for this paper, when there was one. A renewal is usually both. */
    private UUID expensePublicId;

    private String notes;
}
