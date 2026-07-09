package com.crm.travelcrm.auth.entity;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;

@Entity
@Table(
    name = "users",
    uniqueConstraints = @UniqueConstraint(
            name = "uq_user_email_tenant",
            columnNames = {"email", "tenant_id"}),
    indexes = {
        @Index(name = "idx_user_email",  columnList = "email"),
        @Index(name = "idx_user_tenant", columnList = "tenant_id"),
        @Index(name = "idx_user_role",   columnList = "role")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity implements UserDetails {

    // NULL tenant_id = SUPERADMIN (platform-level user, belongs to no tenant)
    // Non-null tenant_id = tenant user (ADMIN, MANAGER, TRAVEL_AGENT)
    // No DB-level FK — cross-aggregate reference to tenants.id, enforced at the application layer.
    @Column(name = "tenant_id")
    private Long tenantId;

    // Owning MANAGER for a TRAVEL_AGENT (self-reference by users.id), nullable.
    // Backs lead visibility: a lead is visible to its assigned agent, that
    // agent's manager, and the tenant admin. Null for managers/admins.
    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "full_name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;



    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    // Replaces both `active` and `enabled` — one field, clear meaning
    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    // SuperAdmin (platform) lock — distinct from the tenant admin's isActive toggle. Nullable so the
    // column can be added to an existing users table under ddl-auto=update; null = not locked. A
    // locked user is rejected at login AND on every request (JwtAuthFilter); a tenant admin's
    // active-toggle cannot clear it (only a SuperAdmin unlock).
    @Column(name = "locked")
    private Boolean locked;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 150)
    private String lockedBy;

    // Bumped by a SuperAdmin force-reset / lock to invalidate every live JWT for this user
    // ("reset + kick"): stamped into the token as the 'tv' claim and re-checked on every request
    // (JwtAuthFilter). Nullable so ddl-auto can add it to the existing users table; null = 0.
    @Column(name = "token_version")
    private Integer tokenVersion;

    // NOTE: no @OneToMany(mappedBy = "assignedUser") here on purpose.
    // Lead.assignedUser (the FK side) fully defines the relationship;
    // lead counts come from aggregate queries in LeadRepository, never
    // from materializing a user's whole lead list.

    // ── UserDetails ──────────────────────────────────────────────────────────

    // Effective authorities resolved at load time = legacy role authorities +
    // fine-grained Permission keys (the user's saved map, or the role default).
    // @Transient: never persisted. Set by UserDetailsServiceImpl on every load; when
    // left unset (e.g. login token issuance) getAuthorities() falls back to role-only.
    @Transient
    @JsonIgnore
    private transient Collection<? extends GrantedAuthority> effectiveAuthorities;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return effectiveAuthorities != null ? effectiveAuthorities : role.authorities();
    }

    @Override public String getUsername()              { return email; }
    // Safety net: entities should never reach JSON responses, but if one does,
    // the password hash must not be serialized.
    @JsonIgnore
    @Override public String getPassword()              { return password; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return locked == null || !locked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return isActive != null && isActive; }

    // Convenience
    public boolean isSuperAdmin() { return role == Role.SUPERADMIN; }
    public boolean belongsToTenant(Long tid) { return tid.equals(this.tenantId); }

    /** Current token version, treating a null (pre-migration) value as 0. */
    public int getTokenVersionOrZero() { return tokenVersion == null ? 0 : tokenVersion; }
}