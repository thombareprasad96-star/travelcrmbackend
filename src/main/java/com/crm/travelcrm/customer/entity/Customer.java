package com.crm.travelcrm.customer.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * Tenant-scoped customer master record.
 *
 * <p>Extends {@link BaseTenantEntity}, so it inherits {@code id}, {@code publicId}
 * (the only identifier exposed in APIs), audit columns, soft-delete and the
 * Hibernate {@code tenantFilter}. {@code tenantId} is auto-stamped by
 * {@code TenantEntityListener} on persist.</p>
 *
 * <p>Booking-derived metrics (lifetime spend, booking count, last booking date)
 * are intentionally <b>not</b> stored here — they are computed on demand from the
 * {@code bookings} table via {@code customer_id}, keeping this entity the single
 * source of truth for profile data only.</p>
 */
@Entity
@Table(
        name = "customers",
        indexes = {
                @Index(name = "idx_customer_tenant",        columnList = "tenant_id"),
                @Index(name = "idx_customer_code",          columnList = "tenant_id,customer_code"),
                @Index(name = "idx_customer_status",        columnList = "tenant_id,status"),
                @Index(name = "idx_customer_type",          columnList = "tenant_id,customer_type"),
                @Index(name = "idx_customer_tier",          columnList = "tenant_id,loyalty_tier"),
                @Index(name = "idx_customer_phone",         columnList = "phone")
        }
        // Uniqueness of (tenant_id, phone) and (tenant_id, customer_code) is enforced by
        // soft-delete-aware PARTIAL unique indexes (uq_customers_phone_tenant /
        // uq_customers_code_tenant, see db/indexes.sql), NOT absolute @UniqueConstraints.
        // An absolute constraint would block reusing a phone after the owning customer is
        // moved to Trash — which broke the lead→booking re-conversion flow.
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Customer extends BaseTenantEntity implements Ownable {

    // Row-level owner: the sub-agent / user who created this customer (per-user data scoping).
    // Nullable — pre-existing & system rows have none. Stamped on create by OwnershipEntityListener;
    // read by CustomerAccessGuard + list filters (Phase 2). Distinct from created_by (audit email).
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Human-friendly business code, e.g. {@code CUS10001}. Unique per tenant. */
    @Column(name = "customer_code", nullable = false, length = 20)
    private String customerCode;

    // ── Contact ────────────────────────────────────────────────────────────────

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /**
     * E.164-canonical form of {@link #phone}, derived on write. A SHADOW column, exactly like
     * {@code Lead.phoneNormalized} and for the same reason.
     *
     * <p>{@link #phone} stays the raw string a human typed, because
     * {@code uq_customers_phone_tenant} and every historical row are keyed on it and rewriting the
     * format in place would break those matches. But raw strings cannot answer "do we already know
     * this person?": "+919812345678", "9812345678" and "+91 98765 43210" are three different people
     * to a string comparison. This column is what makes the existing-customer match on lead creation
     * reliable rather than luck.
     *
     * <p>Nullable, and a null is never a match — {@code PhoneCanonicalizer} returns null rather than
     * guess at a number it cannot parse, and treating those as equal would collapse every
     * unparseable phone onto one another.
     */
    @Column(name = "phone_normalized", length = 20)
    private String phoneNormalized;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "alternate_phone", length = 20)
    private String alternatePhone;

    // ── Provenance ───────────────────────────────────────────────────────────
    // Set to the originating lead's internal id ONLY when this customer row was
    // auto-created by a lead→booking conversion. Null for manually-entered
    // customers. The cancel-cleanup uses this to auto-Trash ONLY the customers it
    // created itself, never a customer a user typed in by hand.
    @Column(name = "created_from_lead_id")
    private Long createdFromLeadId;

    // ── Classification ───────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 30)
    @Builder.Default
    private CustomerType type = CustomerType.INDIVIDUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "comm_pref", length = 30)
    private CommunicationPreference commPref;

    @Enumerated(EnumType.STRING)
    @Column(name = "loyalty_tier", nullable = false, length = 20)
    @Builder.Default
    private LoyaltyTier tier = LoyaltyTier.BRONZE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CustomerStatus status = CustomerStatus.ACTIVE;

    // ── Address ────────────────────────────────────────────────────────────────

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "pincode", length = 10)
    private String pincode;

    // ── Important dates & documents ──────────────────────────────────────────

    @Column(name = "birthday")
    private LocalDate birthday;

    @Column(name = "anniversary")
    private LocalDate anniversary;

    @Column(name = "passport_no", length = 30)
    private String passportNo;

    @Column(name = "pan_no", length = 15)
    private String panNo;

    @Column(name = "aadhar_no", length = 20)
    private String aadharNo;

    @Column(name = "documents", columnDefinition = "TEXT")
    private String documents;

    // ── Free text ──────────────────────────────────────────────────────────────

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}