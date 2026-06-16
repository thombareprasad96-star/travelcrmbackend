package com.crm.travelcrm.vendor.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.vendor.dto.request.*;
import com.crm.travelcrm.vendor.dto.response.VendorResponseDTO;
import com.crm.travelcrm.vendor.dto.response.VendorStatsDTO;

import java.util.List;
import java.util.Map;

public interface VendorService {

    PagedApiResponse<VendorResponseDTO> getAll(int page, int size, String sortBy, String sortDir);

    VendorResponseDTO getById(Long id);

    VendorResponseDTO getByCode(String code);

    VendorResponseDTO create(VendorRequestDTO request);

    VendorResponseDTO update(Long id, VendorRequestDTO request);

    VendorResponseDTO updateStatus(Long id, VendorStatusUpdateDTO request);

    VendorResponseDTO updatePayment(Long id, VendorPaymentUpdateDTO request);

    void delete(Long id);

    List<VendorResponseDTO> filter(String status, String type, String payStatus);

    List<VendorResponseDTO> search(String q);

    List<VendorResponseDTO> getByType(String type);

    VendorStatsDTO getStats();

    List<Map<String, Object>> getBookings(Long id);

    VendorResponseDTO rateVendor(Long id, VendorRatingDTO request);

    byte[] exportCsv();

    Map<String, String> sendEmail(Long id, VendorEmailDTO request);
}