package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.AssignVendorRequest;
import com.crm.travelcrm.booking.dto.request.CreateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.response.BookingServiceItemResponse;

import java.util.List;
import java.util.UUID;

/**
 * Detailed service lines on a booking (hotel, flight, transfer …) with vendor assignment.
 * All lookups are scoped to the booking (and tenant) so a foreign service publicId returns 404,
 * never another booking's data.
 */
public interface BookingServiceItemService {

    List<BookingServiceItemResponse> getServiceItems(UUID bookingPublicId);

    BookingServiceItemResponse addServiceItem(UUID bookingPublicId, CreateBookingServiceItemRequest request);

    BookingServiceItemResponse updateServiceItem(UUID bookingPublicId, UUID itemPublicId,
                                                 UpdateBookingServiceItemRequest request);

    void deleteServiceItem(UUID bookingPublicId, UUID itemPublicId);

    BookingServiceItemResponse assignVendor(UUID bookingPublicId, UUID itemPublicId, AssignVendorRequest request);
}