package com.crm.travelcrm.tenent.service;

import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.TenantSummaryResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Platform (SuperAdmin) tenant management. All external identifiers are {@code publicId} (UUID);
 * every mutation is recorded to the platform audit trail. Status transitions go through the
 * dedicated lifecycle methods, never {@link #updateTenant}.
 */
public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request);

    Page<TenantSummaryResponse> listTenants(String search, TenantStatus status,
                                            boolean deleted, Pageable pageable);

    TenantResponse getTenant(UUID publicId);

    TenantResponse updateTenant(UUID publicId, UpdateTenantRequest request);

    /** Assign / upgrade / downgrade the tenant's plan; applies the plan's seat limit. */
    TenantResponse changePlan(UUID publicId, TenantPlan plan);

    void suspend(UUID publicId);

    void reactivate(UUID publicId);

    void softDelete(UUID publicId);

    void restore(UUID publicId);

    /**
     * Irreversible hard-delete of an already soft-deleted tenant: physically removes the tenant + its
     * users (billing/audit snapshots are retained by design). {@code organizationCode} must echo the
     * tenant's code as a double-confirmation. Audited {@code TENANT_HARD_DELETE}.
     */
    void hardDelete(UUID publicId, String organizationCode);
}