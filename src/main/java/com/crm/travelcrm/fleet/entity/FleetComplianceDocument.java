package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDocumentStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One legal paper for one vehicle or one driver, with its full renewal history.
 *
 * <p><b>Replaces four date columns on {@code FleetVehicle} and one on {@code FleetDriver}.</b> That
 * shape could hold a single expiry per document type and nothing else — no number, no issuing
 * authority, no jurisdiction, no cost, and crucially no history: renewing overwrote the date, and
 * the certificate that was valid last March became unreconstructable. It also could not represent a
 * vehicle carrying both a national and a state permit, or any of the papers an operator is actually
 * stopped for (Green Card, fitness, VLTD, PSV badge).
 *
 * <h2>Renewal never overwrites</h2>
 * A renewal INSERTS a new row and marks the previous one {@link FleetDocumentStatus#SUPERSEDED},
 * linked by {@link #supersedes}. The old number, authority, validity and the expense that paid for
 * it all survive — which is the point, because "what was valid on this past date" is a scrutiny
 * question, and it becomes free once validity is an interval instead of a column.
 *
 * <h2>Exactly one owner</h2>
 * A row belongs to a vehicle or to a driver, never both and never neither — enforced by a CHECK, not
 * by convention. A polymorphic owner with two nullable FKs is only safe while something says so.
 *
 * <h2>Retention</h2>
 * Deliberately NOT registered in {@code TrashableType}. The 30-day purge would hard-delete statutory
 * records whose retention requirement is eight years. Soft-delete still hides a row; nothing purges
 * it. Its owning vehicle/driver must also refuse to be trashed while documents survive — otherwise
 * the parent purges and the FK takes these with it.
 */
@Entity
@Table(name = "fleet_compliance_documents", indexes = {
        @Index(name = "idx_fleet_doc_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_doc_vehicle", columnList = "tenant_id,vehicle_id,category"),
        @Index(name = "idx_fleet_doc_driver", columnList = "tenant_id,driver_id,category"),
        // The expiry scan and the dashboard both sweep on this.
        @Index(name = "idx_fleet_doc_expiry", columnList = "tenant_id,valid_until")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetComplianceDocument extends BaseTenantEntity {

    /** Which side of the fleet this belongs to. Denormalised so a list query needs no CASE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    private FleetRefType ownerType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", foreignKey = @ForeignKey(name = "fk_fleet_doc_vehicle"))
    private FleetVehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", foreignKey = @ForeignKey(name = "fk_fleet_doc_driver"))
    private FleetDriver driver;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private FleetDocumentCategory category;

    @Column(name = "document_number", length = 60)
    private String documentNumber;

    @Column(name = "issuing_authority", length = 150)
    private String issuingAuthority;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    /** Required for a state permit / road tax — a permit for UP is not a permit for Rajasthan. */
    @Column(name = "state_code", length = 40)
    private String stateCode;

    /** Border post, for a Nepal entry. */
    @Column(name = "border_post", length = 80)
    private String borderPost;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** Null means open-ended (a lifetime registration). Everything else drives the expiry alerts. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    /**
     * Nepal entry carries a hard exit deadline that is NOT the same as validity: the paper may be
     * valid for a month while the vehicle must be back across the border in seven days. Overstaying
     * is a fine at the border, so it gets its own field and its own countdown.
     */
    @Column(name = "exit_deadline")
    private LocalDate exitDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private FleetDocumentStatus status = FleetDocumentStatus.ACTIVE;

    /**
     * Per-document override of {@link FleetDocumentCategory#blocksByDefault()}. Null = use the
     * category default. The dispatcher wanted compliance to warn and never block; the accountant
     * wanted an expired PSV badge to refuse the assignment outright. Both are right about different
     * papers, so the paper decides and this allows the exception.
     */
    @Column(name = "blocking")
    private Boolean blocking;

    /** The row this renewal replaces. Null on an original. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_id", foreignKey = @ForeignKey(name = "fk_fleet_doc_supersedes"))
    private FleetComplianceDocument supersedes;

    /**
     * What this paper cost, when it cost anything. A permit renewal is both a compliance document
     * AND a money event — neither row replaces the other, and linking them is how the cross-border
     * report shows a permit's cost beside its validity.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id", foreignKey = @ForeignKey(name = "fk_fleet_doc_expense"))
    private FleetExpense expense;

    /**
     * Set on rows created by the legacy backfill, where only an expiry date was known. The number,
     * issue date and authority are genuinely unknown and must NOT be invented — the UI shows these
     * as needing review rather than presenting a blank as a fact.
     */
    @Column(name = "needs_review", nullable = false)
    @Builder.Default
    private boolean needsReview = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 300)
    private String revokeReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Whether an expired instance of this document refuses an assignment. */
    public boolean isBlocking() {
        return blocking != null ? blocking : category.blocksByDefault();
    }

    /**
     * Status derived from the dates. Stored decisions ({@code SUPERSEDED}, {@code REVOKED}) win —
     * a superseded row is not "expired", it is history.
     *
     * @param warnWithinDays how far ahead counts as EXPIRING
     */
    public FleetDocumentStatus deriveStatus(LocalDate today, int warnWithinDays) {
        if (status == FleetDocumentStatus.SUPERSEDED || status == FleetDocumentStatus.REVOKED) {
            return status;
        }
        if (validUntil == null) return FleetDocumentStatus.ACTIVE;   // open-ended
        if (validUntil.isBefore(today)) return FleetDocumentStatus.EXPIRED;
        if (!validUntil.isAfter(today.plusDays(warnWithinDays))) return FleetDocumentStatus.EXPIRING;
        return FleetDocumentStatus.ACTIVE;
    }

    /**
     * Is this paper valid through {@code date}?
     *
     * <p>Checked against a TRIP'S RETURN DATE, never today. A permit valid tomorrow but expired on
     * day six of a Char Dham run is a vehicle impounded at a barrier — and "valid today" would have
     * cheerfully let it leave.
     */
    public boolean isValidThrough(LocalDate date) {
        if (status == FleetDocumentStatus.REVOKED) return false;
        if (validUntil == null) return true;
        return !validUntil.isBefore(date);
    }
}
