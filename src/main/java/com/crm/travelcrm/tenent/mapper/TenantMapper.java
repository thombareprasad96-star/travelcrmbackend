package com.crm.travelcrm.tenent.mapper;

import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.TenantSummaryResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.entity.Tenant;
import org.springframework.stereotype.Component;

@Component
public class TenantMapper {

    /** Full detail / create response. {@code userCount} is populated by the service. */
    public TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
                .tenantId(tenant.getPublicId())
                .organizationName(tenant.getOrganizationName())
                .organizationCode(tenant.getOrganizationCode())
                .email(tenant.getEmail())
                .phone(tenant.getPhone())
                .address(tenant.getAddress())
                .plan(tenant.getPlan())
                .status(tenant.getStatus())
                .maxUsers(tenant.getMaxUsers())
                .subscriptionStartDate(tenant.getSubscriptionStartDate())
                .subscriptionEndDate(tenant.getSubscriptionEndDate())
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .deletedAt(tenant.getDeletedAt())
                .build();
    }

    /** Lean list row. {@code userCount} / {@code unpaidCount} are supplied by the service. */
    public TenantSummaryResponse toSummary(Tenant tenant, long userCount, long unpaidCount) {
        return TenantSummaryResponse.builder()
                .publicId(tenant.getPublicId())
                .organizationName(tenant.getOrganizationName())
                .organizationCode(tenant.getOrganizationCode())
                .email(tenant.getEmail())
                .phone(tenant.getPhone())
                .plan(tenant.getPlan())
                .status(tenant.getStatus())
                .subscriptionEndDate(tenant.getSubscriptionEndDate())
                .maxUsers(tenant.getMaxUsers())
                .userCount(userCount)
                .unpaidCount(unpaidCount)
                .createdAt(tenant.getCreatedAt())
                .deleted(tenant.isDeleted())
                .build();
    }

    /**
     * Applies editable basics. {@code organizationCode} (subdomain) and {@code status} are excluded
     * by design; {@code plan} / {@code maxUsers} are applied only when provided.
     */
    public void updateEntity(UpdateTenantRequest request, Tenant tenant) {
        tenant.setOrganizationName(request.getOrganizationName());
        tenant.setEmail(request.getEmail());
        tenant.setPhone(request.getPhone());
        tenant.setAddress(request.getAddress());
        tenant.setSubscriptionStartDate(request.getSubscriptionStartDate());
        tenant.setSubscriptionEndDate(request.getSubscriptionEndDate());
        if (request.getMaxUsers() != null) {
            tenant.setMaxUsers(request.getMaxUsers());
        }
    }
}