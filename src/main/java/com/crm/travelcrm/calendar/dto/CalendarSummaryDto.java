package com.crm.travelcrm.calendar.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The calendar "dashboard" snapshot for a reference date (default: today) — the top KPI cards and
 * the right-rail lists in the reference image. Tenant-wide; CRM_FULL-gated (sub-agents excluded).
 * Day boundaries are UTC (consistent with the reminder module's due-today logic).
 */
@Getter
@Builder
public class CalendarSummaryDto {

    private LocalDate date;

    // ── KPI cards ────────────────────────────────────────────────────────────────────────────
    private long todaysFollowups;
    private long activeTrips;
    private BigDecimal paymentsDueAmount;
    private long paymentsDueCount;
    private long flightsToday;
    private long hotelCheckinsToday;
    private long visaToday;
    private long newLeadsToday;
    private BigDecimal revenueThisMonth;

    // ── Right-rail lists ─────────────────────────────────────────────────────────────────────
    private List<CalendarEvent> todaysSchedule;
    private PaymentDueSummary paymentDueSummary;
    private List<CalendarEvent> tripsStartingToday;
    private List<CalendarEvent> flightDepartures;
    private List<CalendarEvent> hotelCheckins;
    private List<CalendarEvent> visaAppointments;

    /**
     * Outstanding split by the derive-from-travel-date rule: overdue = travel date already past with
     * a balance owing; due-today = travel date is the reference date with a balance owing.
     */
    @Getter
    @Builder
    public static class PaymentDueSummary {
        private BigDecimal totalOverdue;
        private long overdueCount;
        private BigDecimal dueToday;
        private long dueTodayCount;
    }
}