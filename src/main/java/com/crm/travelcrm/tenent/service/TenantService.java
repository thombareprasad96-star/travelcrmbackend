package com.crm.travelcrm.tenent.service;

import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;

import java.util.List;

public interface TenantService {

    TenantResponse createTenant(CreateTenantRequest request);

    List<TenantResponse> getAllTenants();

    TenantResponse getTenantById(Long id);

    TenantResponse updateTenant(Long id, UpdateTenantRequest request);

    void deleteTenant(Long id);
}