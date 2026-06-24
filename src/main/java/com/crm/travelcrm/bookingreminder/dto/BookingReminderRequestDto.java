package com.crm.travelcrm.bookingreminder.dto;

import com.crm.travelcrm.bookingreminder.entity.BookingReminderStatus;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

// Create/update payload — mirrors bookingReminderService.js.
@Data
public class BookingReminderRequestDto {

    @NotBlank(message = "Booking code is required")
    @Size(max = 50)
    private String bookingCode;

    @NotBlank(message = "Customer name is required")
    @Size(max = 255)
    private String customerName;

    @Size(max = 30)
    private String phone;

    @Size(max = 255)
    private String destination;

    @NotNull(message = "Reminder type is required")
    private BookingReminderType reminderType;

    @Size(max = 1000)
    private String message;

    private Instant travelDate;

    @NotNull(message = "Reminder date is required")
    private Instant reminderDate;

    // Optional on create (defaults to Pending); allowed on update.
    private BookingReminderStatus status;

    private Double amount;
}