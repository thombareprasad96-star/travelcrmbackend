package com.crm.travelcrm.booking.exception;

public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(Long id) {
        super("Booking not found with id: " + id);
    }

    public BookingNotFoundException(String code) {
        super("Booking not found with code: " + code);
    }
}