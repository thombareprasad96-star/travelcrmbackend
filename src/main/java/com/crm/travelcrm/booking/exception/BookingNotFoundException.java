package com.crm.travelcrm.booking.exception;

import java.util.UUID;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(UUID publicId) {
        super("Booking not found with id: " + publicId);
    }

    public BookingNotFoundException(Long id) {
        super("Booking not found with id: " + id);
    }

    public BookingNotFoundException(String code) {
        super("Booking not found with code: " + code);
    }
}