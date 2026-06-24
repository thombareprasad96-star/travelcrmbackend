package com.crm.travelcrm.bookingreminder.dto;

import com.crm.travelcrm.bookingreminder.entity.BookingReminderStatus;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderType;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDateTime;

// Raw response (no ApiResponse envelope) with the numeric id — matches the sibling
// reminder module and bookingReminderService.js (which reads res.data directly).
@Getter
@Builder
public class BookingReminderResponseDto {
    private Long                  id;
    private String                bookingCode;
    private String                customerName;
    private String                phone;
    private String                destination;
    private BookingReminderType   reminderType;
    private String                message;
    private Instant               travelDate;
    private Instant               reminderDate;
    private BookingReminderStatus status;
    private Double                amount;
    private LocalDateTime         createdAt;
}