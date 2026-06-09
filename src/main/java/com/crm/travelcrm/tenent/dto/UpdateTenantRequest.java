package com.crm.travelcrm.tenent.dto;

import com.crm.travelcrm.tenent.enums.TenantStatus;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

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

    @NotNull(message = "Status is required")
    private TenantStatus status;

    @NotNull(message = "Subscription start date is required")
    private LocalDate subscriptionStartDate;

    @NotNull(message = "Subscription end date is required")
    private LocalDate subscriptionEndDate;
}