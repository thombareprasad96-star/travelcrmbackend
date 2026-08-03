package com.crm.travelcrm.customer.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.customer.dto.request.CreateCustomerRequest;
import com.crm.travelcrm.customer.dto.request.CustomerResolveRequest;
import com.crm.travelcrm.customer.dto.request.StatusUpdateRequest;
import com.crm.travelcrm.customer.dto.request.TierUpdateRequest;
import com.crm.travelcrm.customer.dto.request.UpdateCustomerRequest;
import com.crm.travelcrm.customer.dto.response.CustomerBookingResponse;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.dto.response.CustomerResponse;
import com.crm.travelcrm.customer.dto.response.CustomerStatsResponse;
import com.crm.travelcrm.customer.entity.Customer;

import java.util.List;
import java.util.UUID;

/**
 * Customer business operations. Every method is implicitly tenant-scoped via
 * {@code TenantContext}; the contract speaks only in {@code publicId} (UUID) and
 * never in internal numeric ids.
 */
public interface CustomerService {

    CustomerResponse create(CreateCustomerRequest request);

    PagedApiResponse<CustomerResponse> getAll(int page, int size, String sortBy, String sortDir,
                                              String q, String status, String type, String tier);

    CustomerResponse getById(UUID publicId);

    CustomerResponse searchByPhone(String phone);

    /**
     * Non-throwing existing-customer probe by phone and/or email, backing the lead form's
     * "Customer found" popup. Returns {@code matched: false} rather than throwing when nothing
     * matches — a new contact is the normal case, not an error.
     */
    CustomerMatchResponse lookup(String phone, String email);

    /**
     * Resolve the customer for a booking: reuse an existing one by publicId (optionally syncing a
     * few profile fields), or create a new one. Lives here, not in the booking module, so the
     * duplicate-phone and trashed-restore rules stay in one place.
     */
    Customer resolveOrCreate(CustomerResolveRequest request, Long leadId);

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