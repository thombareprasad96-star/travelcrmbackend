package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CancelBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.LeadConversionRequestDTO;
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

    /**
     * Convert a qualified lead (optionally carrying an accepted quotation) into a booking.
     * Atomic: resolves/creates the customer, carries over the quotation/lead details, mints a
     * tenant-scoped reference, flips the lead to CONVERTED with back-links, and links the booking
     * back to its source lead + quotation. Guards against creating a duplicate booking for a lead.
     */
    BookingResponseDTO convertLeadToBooking(UUID leadPublicId, LeadConversionRequestDTO request);

    BookingResponseDTO getById(UUID publicId);

    BookingResponseDTO getByCode(String code);

    PagedApiResponse<BookingResponseDTO> getAll(int page, int size, String sortBy, String sortDir);

    BookingResponseDTO update(UUID publicId, UpdateBookingRequestDTO request);

    BookingResponseDTO updateStatus(UUID publicId, StatusUpdateRequestDTO request);

    /**
     * Cancel a booking with an explicit choice about the associated lead. The booking is ALWAYS
     * retained as CANCELLED (never destroyed). MOVE_TO_LEAD re-activates the lead (REOPENED);
     * PERMANENT_DELETE_LEAD hard-deletes the lead (high-privilege) and nulls the booking's lead
     * link so it never dangles. A COMPLETED booking cannot be cancelled. Atomic.
     */
    BookingResponseDTO cancel(UUID publicId, CancelBookingRequestDTO request);

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