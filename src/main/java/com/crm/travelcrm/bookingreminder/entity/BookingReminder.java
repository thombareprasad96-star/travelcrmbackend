package com.crm.travelcrm.bookingreminder.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * A booking-related reminder (payment due, document, visa, travel date, …).
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity}; soft-deleted via {@code deletedAt}.
 * Field names mirror the frontend {@code bookingReminderService.js} contract. Sibling of
 * the {@code reminder} module — like it, responses are returned as raw objects with the
 * numeric {@code id} (no {@code ApiResponse} envelope).
 *
 * <p>{@code bookingCode}/{@code customerName} are free-text snapshots (no DB-level FK to a
 * Booking) so a reminder can be created for any booking reference the frontend supplies.
 */
@Entity
@Table(name = "booking_reminders", indexes = {
        @Index(name = "idx_booking_reminder_tenant", columnList = "tenant_id"),
        @Index(name = "idx_booking_reminder_status", columnList = "status"),
        @Index(name = "idx_booking_reminder_code",   columnList = "booking_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingReminder extends BaseTenantEntity {

    @Column(name = "booking_code", nullable = false, length = 50)
    private String bookingCode;

    @Column(name = "customer_name", nullable = false, length = 255)
    private String customerName;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "destination", length = 255)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 20)
    private BookingReminderType reminderType;

    @Column(name = "message", length = 1000)
    private String message;

    @Column(name = "travel_date")
    private Instant travelDate;

    @Column(name = "reminder_date", nullable = false)
    private Instant reminderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private BookingReminderStatus status = BookingReminderStatus.Pending;

    @Column(name = "amount")
    private Double amount;
}