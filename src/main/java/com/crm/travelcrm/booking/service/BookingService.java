package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.common.dto.PagedApiResponse;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BookingService {
    BookingResponseDTO create(CreateBookingRequestDTO request);

    BookingResponseDTO createFromLead(Long leadId);

    BookingResponseDTO getById(UUID publicId);

    BookingResponseDTO getByCode(String code);

    PagedApiResponse<BookingResponseDTO> getAll(int page, int size, String sortBy, String sortDir);

    BookingResponseDTO update(UUID publicId, UpdateBookingRequestDTO request);

    BookingResponseDTO updateStatus(UUID publicId, StatusUpdateRequestDTO request);

    BookingResponseDTO updatePayment(UUID publicId, PaymentUpdateRequestDTO request);

    void delete(UUID publicId);

    List<BookingResponseDTO> getByCustomerId(Long customerId);

    List<BookingResponseDTO> search(String keyword);

    List<BookingResponseDTO> filter(
            BookingStatus status,
            PaymentStatus paymentStatus,
            Integer bookingMonth,
            Integer travelMonth,
            Long customerId,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    );

    BookingStatsResponseDTO getStats();

    BookingPageSummaryResponseDTO getPageSummary(int page, int size, String sortBy, String sortDir);

    void sendVoucher(UUID publicId);
}