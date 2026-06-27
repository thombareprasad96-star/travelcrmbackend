package com.crm.travelcrm.portal.auth.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * A customer's self-service portal identity — the traveler auth realm, kept strictly separate
 * from the staff {@code User}/{@code Role} world. One account per (tenant, customer); it links to
 * a {@code Customer} by the internal {@code customerId} (logical FK, validated tenant-scoped) and
 * carries only login state (the current one-time passcode + status), never a password.
 *
 * <p>Tenant-scoped and soft-deletable via {@link BaseTenantEntity}. Provisioned lazily the first
 * time a known customer requests an OTP (see {@code TravelerAuthService}); travelers cannot
 * self-register without an existing customer record.</p>
 */
@Entity
@Table(
        name = "traveler_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_traveler_tenant_customer", columnNames = {"tenant_id", "customer_id"}),
        indexes = {
                @Index(name = "idx_traveler_tenant",     columnList = "tenant_id"),
                @Index(name = "idx_traveler_identifier", columnList = "login_identifier")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TravelerAccount extends BaseTenantEntity {

    /** Logical FK → customers.id (same tenant). Never exposed; the API speaks publicId. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Snapshot of the customer's publicId — lets the auth filter build the principal without a join. */
    @Column(name = "customer_public_id", nullable = false)
    private java.util.UUID customerPublicId;

    /** Convenience snapshot so we can label the account without loading the customer. */
    @Column(name = "customer_name", length = 150)
    private String customerName;

    /** Normalised phone or email the traveler logs in with. */
    @Column(name = "login_identifier", nullable = false, length = 150)
    private String loginIdentifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TravelerAccountStatus status = TravelerAccountStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // OTP issuance/verification lives in the shared, plug-and-play otp module (OtpService) — keyed
    // by (PORTAL_LOGIN, identifier) — so this entity holds no passcode state.

    public boolean isActive() {
        return status == TravelerAccountStatus.ACTIVE;
    }
}
