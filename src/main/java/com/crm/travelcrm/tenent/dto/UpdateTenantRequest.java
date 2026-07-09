package com.crm.travelcrm.tenent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * Edit a tenant's basics. Status and plan are intentionally NOT here — status transitions go through
 * the dedicated suspend / reactivate / delete / restore endpoints, and plan changes through the
 * change-plan endpoint, so each has one audited path. {@code organizationCode} (subdomain) is
 * immutable after creation.
 */
@Data
public class UpdateTenantRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 150)
    private String organizationName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Size(max = 20)
    private String phone;

    @Size(max = 255)
    private String address;

    // Optional per-tenant seat override — applied only when present (plan assignment sets the default).
    @Min(value = 1, message = "Max users must be at least 1")
    private Integer maxUsers;

    @NotNull(message = "Subscription start date is required")
    private LocalDate subscriptionStartDate;

    @NotNull(message = "Subscription end date is required")
    private LocalDate subscriptionEndDate;
}