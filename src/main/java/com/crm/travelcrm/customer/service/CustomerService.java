package com.crm.travelcrm.customer.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.customer.dto.request.CreateCustomerRequest;
import com.crm.travelcrm.customer.dto.request.StatusUpdateRequest;
import com.crm.travelcrm.customer.dto.request.TierUpdateRequest;
import com.crm.travelcrm.customer.dto.request.UpdateCustomerRequest;
import com.crm.travelcrm.customer.dto.response.CustomerBookingResponse;
import com.crm.travelcrm.customer.dto.response.CustomerResponse;
import com.crm.travelcrm.customer.dto.response.CustomerStatsResponse;

import java.util.List;
import java.util.UUID;

/**
 * Customer business operations. Every method is implicitly tenant-scoped via
 * {@code TenantContext}; the contract speaks only in {@code publicId} (UUID) and
 * never in internal numeric ids.
 */
public interface CustomerService {

    CustomerResponse create(CreateCustomerRequest request);

    PagedApiResponse<CustomerResponse> getAll(int page, int size, String sortBy, String sortDir);

    CustomerResponse getById(UUID publicId);

    CustomerResponse searchByPhone(String phone);

    List<CustomerResponse> searchByName(String name);

    CustomerResponse update(UUID publicId, UpdateCustomerRequest request);

    CustomerResponse updateStatus(UUID publicId, StatusUpdateRequest request);

    CustomerResponse updateTier(UUID publicId, TierUpdateRequest request);

    void delete(UUID publicId);

    /** All filters are optional; any combination is ANDed together. */
    List<CustomerResponse> filter(String status, String type, String tier);

    List<CustomerBookingResponse> getBookingHistory(UUID publicId);

    CustomerStatsResponse getStats();

    byte[] exportCsv();
}