
package com.crm.travelcrm.tenent.dto;

import com.crm.travelcrm.auth.util.UsernamePolicy;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateTenantRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 150, message = "Organization name must not exceed 150 characters")
    private String organizationName;

    @NotBlank(message = "Organization code is required")
    @Size(max = 50, message = "Organization code must not exceed 50 characters")
    private String organizationCode;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Size(max = 20, message = "Phone must not exceed 20 characters")
    private String phone;

    @Size(max = 255, message = "Address must not exceed 255 characters")
    private String address;

    @NotNull(message = "Subscription start date is required")
    private LocalDate subscriptionStartDate;

    @NotNull(message = "Subscription end date is required")
    private LocalDate subscriptionEndDate;

    // Plan & limits (all optional). Defaults applied in the service: plan=STARTER,
    // status=TRIAL, maxUsers=5. Rich plan entitlements arrive in the Subscriptions phase.
    private TenantPlan plan;

    private TenantStatus status;

    @Min(value = 1, message = "Max users must be at least 1")
    private Integer maxUsers;

    // Admin user fields
    //
    // NOTE: despite the name, `adminUsername` is the admin's DISPLAY NAME (it is persisted to
    // User.name) and always has been. It is deliberately NOT repurposed as the login identifier:
    // the console posts a human name like "Prasad Thombare" here, which contains a space and would
    // fail username validation — silently breaking tenant creation for an un-updated console.
    @NotBlank(message = "Admin username is required")
    @Size(max = 100, message = "Admin username must not exceed 100 characters")
    private String adminUsername;

    // The admin's actual LOGIN identifier. Optional: this is a SuperAdmin bootstrap flow, and the
    // SuperAdmin creating the organization does not necessarily know what login the customer wants.
    // When blank the service derives one from adminEmail's local-part and returns it on the
    // response (TenantResponse.adminLoginUsername) so it can be handed to the customer.
    // Contrast /api/users and sub-agent creation, where a human is filling in the form and the
    // username is required — inventing one there would hide a typo instead of surfacing it.
    @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH,
          message = "Admin login username must be 3–80 characters")
    @Pattern(regexp = UsernamePolicy.PATTERN, message = UsernamePolicy.PATTERN_MESSAGE)
    private String adminLoginUsername;

    @NotBlank(message = "Admin email is required")
    @Email(message = "Invalid admin email format")
    private String adminEmail;

    @NotBlank(message = "Admin password is required")
    @Size(min = 6, message = "Admin password must be at least 6 characters")
    private String adminPassword;
}