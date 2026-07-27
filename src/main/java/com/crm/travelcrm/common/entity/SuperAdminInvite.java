package com.crm.travelcrm.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "super_admin_invites", indexes = {
        @Index(name = "idx_sai_token_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_sai_invited_email", columnList = "invited_email"),
        @Index(name = "idx_sai_expires_at", columnList = "expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SuperAdminInvite extends BaseEntity {

    @Column(name = "invited_email", nullable = false, length = 150)
    private String invitedEmail;

    @Column(name = "invited_name", nullable = false, length = 150)
    private String invitedName;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "mfa_secret_enc", columnDefinition = "TEXT")
    private String mfaSecretEnc;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_by_super_admin_id")
    private Long createdBySuperAdminId;

    @Column(name = "created_by_email", length = 150)
    private String createdByEmail;

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }
}
