package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CreateBookingPaymentRequest;
import com.crm.travelcrm.booking.dto.response.BookingPaymentResponse;

import java.util.List;
import java.util.UUID;

/**
 * Itemised payment ledger for a booking. Every add/delete keeps the parent booking's
 * {@code paidAmount} and {@code paymentStatus} in step. All lookups are scoped to the booking
 * (and tenant) so a foreign payment publicId can never be reached.
 */
public interface BookingPaymentService {

    List<BookingPaymentResponse> getPayments(UUID bookingPublicId);

    BookingPaymentResponse addPayment(UUID bookingPublicId, CreateBookingPaymentRequest request);

    void deletePayment(UUID bookingPublicId, UUID paymentPublicId);
}