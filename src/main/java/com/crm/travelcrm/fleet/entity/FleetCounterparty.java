package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Someone the operator hires vehicles from, or has work done by — an attached-vehicle owner, a
 * market supplier, a garage.
 *
 * <p><b>Why this table exists.</b> Fleet ships as an independent product as well as a CRM feature,
 * and in a Fleet-only deployment there is no Vendor master. Without a directory of its own, a
 * standalone operator could not record whose vehicle he is running — and attached vehicles are the
 * majority of most Indian fleets, not the exception. It is also what the owner's monthly per-party
 * payout statement is grouped by; free text on the vehicle can never be grouped.
 *
 * <p><b>Not a Vendor, and deliberately not shaped like one.</b> {@code Vendor} spreads across three
 * tables via {@code @SecondaryTable} and carries CRM concerns (ratings, commission, total business).
 * This is flat and holds only what a fleet actually needs: who to call, what to put on a bill, and
 * where to send the money.
 *
 * <p>In CRM mode this table is simply unused — {@code CrmVendorPartyAdapter} wins the
 * {@code FleetPartyPort} binding and the directory is the Vendor master, so nothing is duplicated
 * and nothing has to be kept in sync.
 */
@Entity
@Table(
        name = "fleet_parties",
        indexes = {
                @Index(name = "idx_fleet_party_tenant", columnList = "tenant_id"),
                @Index(name = "idx_fleet_party_name",   columnList = "tenant_id,name")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetCounterparty extends BaseTenantEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "contact_person", length = 120)
    private String contactPerson;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    /** For the hired-vehicle bill and the TDS 194C decision — a transporter's PAN changes the rate. */
    @Column(name = "gstin", length = 20)
    private String gstin;

    @Column(name = "pan", length = 15)
    private String pan;

    // ── Where the money goes. A payout statement without these is a statement nobody can act on. ──
    @Column(name = "bank_name", length = 120)
    private String bankName;

    @Column(name = "account_name", length = 150)
    private String accountName;

    @Column(name = "account_number", length = 40)
    private String accountNumber;

    @Column(name = "ifsc_code", length = 15)
    private String ifscCode;

    @Column(name = "upi_id", length = 100)
    private String upiId;

    /**
     * Standing rate agreed with this party, if any — e.g. a per-day hire rate. Informational: the
     * ledger never derives money from it, because a rate that quietly changes must not rewrite what
     * was already billed.
     */
    @Column(name = "agreed_rate", precision = 12, scale = 2)
    private BigDecimal agreedRate;

    @Column(name = "notes", length = 1000)
    private String notes;

    /** Inactive parties stay on every historical vehicle and bill; they just leave the dropdown. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
