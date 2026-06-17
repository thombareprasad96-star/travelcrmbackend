package com.crm.travelcrm.customer.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
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
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_customer_tenant_phone",
                        columnNames = {"tenant_id", "phone"}
                ),
                @UniqueConstraint(
                        name = "uk_customer_tenant_code",
                        columnNames = {"tenant_id", "customer_code"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Customer extends BaseTenantEntity {

    /** Human-friendly business code, e.g. {@code CUS10001}. Unique per tenant. */
    @Column(name = "customer_code", nullable = false, length = 20)
    private String customerCode;

    // ── Contact ────────────────────────────────────────────────────────────────

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "alternate_phone", length = 20)
    private String alternatePhone;

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