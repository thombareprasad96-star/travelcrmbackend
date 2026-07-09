package com.crm.travelcrm.tenent.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantResponse {

    private UUID tenantId;
    private String organizationName;
    private String organizationCode;
    private String email;
    private String phone;
    private String address;
    private TenantPlan plan;
    private TenantStatus status;
    private Integer maxUsers;
    private Long userCount;
    private LocalDate subscriptionStartDate;
    private LocalDate subscriptionEndDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    // Populated only on create
    private String adminUsername;
    private String message;
}