package com.crm.travelcrm.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;

@Entity
@Table(name = "super_admins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuperAdmin extends BaseEntity implements UserDetails {


    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Builder.Default
    @Column(name = "mfa_enabled")
    private Boolean mfaEnabled = false;

    @Column(name = "mfa_secret_enc", columnDefinition = "TEXT")
    private String mfaSecretEnc;

    @Column(name = "mfa_enabled_at")
    private LocalDateTime mfaEnabledAt;

    @Column(name = "mfa_last_used_at")
    private LocalDateTime mfaLastUsedAt;

    @Builder.Default
    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

    @Builder.Default
    @Column(name = "created_via_invite")
    private Boolean createdViaInvite = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "last_login_user_agent_hash", length = 64)
    private String lastLoginUserAgentHash;

    // Bumped when high-consequence account security state changes so old JWTs stop working.
    @Column(name = "token_version")
    private Integer tokenVersion;

    @Builder.Default
    @Column(name = "failed_login_attempts")
    private Integer failedLoginAttempts = 0;

    @Column(name = "last_failed_login_at")
    private LocalDateTime lastFailedLoginAt;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
    }

    @Override public String getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      {
        return lockedUntil == null || LocalDateTime.now().isAfter(lockedUntil);
    }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return enabled != null && enabled; }

    public boolean isMfaEnabled() {
        return Boolean.TRUE.equals(mfaEnabled);
    }

    public boolean isMustChangePassword() {
        return Boolean.TRUE.equals(mustChangePassword);
    }

    public boolean isCreatedViaInvite() {
        return Boolean.TRUE.equals(createdViaInvite);
    }

    public boolean isSetupComplete() {
        return isMfaEnabled() && !isMustChangePassword();
    }

    public int getTokenVersionOrZero() {
        return tokenVersion == null ? 0 : tokenVersion;
    }

    public void bumpTokenVersion() {
        tokenVersion = getTokenVersionOrZero() + 1;
    }

    public int getFailedLoginAttemptsOrZero() {
        return failedLoginAttempts == null ? 0 : failedLoginAttempts;
    }
}
