package com.crm.travelcrm.tenent.mapper;

import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.entity.Tenant;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TenantMapper {

    public TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
                .tenantId(tenant.getPublicId())
                .organizationName(tenant.getOrganizationName())
                .organizationCode(tenant.getOrganizationCode())
                .email(tenant.getEmail())
                .phone(tenant.getPhone())
                .address(tenant.getAddress())
                .status(tenant.getStatus())
                .subscriptionStartDate(tenant.getSubscriptionStartDate())
                .subscriptionEndDate(tenant.getSubscriptionEndDate())
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }

    public List<TenantResponse> toResponseList(List<Tenant> tenants) {
        return tenants.stream()
                .map(this::toResponse)
                .toList();
    }

    public void updateEntity(UpdateTenantRequest request, Tenant tenant) {
        tenant.setOrganizationName(request.getOrganizationName());
        tenant.setEmail(request.getEmail());
        tenant.setPhone(request.getPhone());
        tenant.setAddress(request.getAddress());
        tenant.setStatus(request.getStatus());
        tenant.setSubscriptionStartDate(request.getSubscriptionStartDate());
        tenant.setSubscriptionEndDate(request.getSubscriptionEndDate());
        // organizationCode is intentionally not updated — immutable after creation
    }
}