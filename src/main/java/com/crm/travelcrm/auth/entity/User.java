package com.crm.travelcrm.auth.entity;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

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
    // Non-null tenant_id = tenant user (ADMIN, MANAGER, AGENT)
    @Column(name = "tenant_id")
    private Long tenantId;

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

    // ── UserDetails ──────────────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return role.authorities();
    }

    @Override public String getUsername()              { return email; }
    @Override public String getPassword()              { return password; }
    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return isActive != null && isActive; }

    // Convenience
    public boolean isSuperAdmin() { return role == Role.SUPERADMIN; }
    public boolean belongsToTenant(Long tid) { return tid.equals(this.tenantId); }
}