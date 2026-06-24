package com.crm.travelcrm.bookingreminder.entity;

// Names match the frontend bookingReminderService.js contract exactly (Jackson
// serializes/deserializes by enum name).
public enum BookingReminderType {
    Payment_due,
    Final_payment,
    Document,
    Visa,
    Travel_date,
    Itinerary
}