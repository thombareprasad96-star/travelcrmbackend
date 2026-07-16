package com.crm.travelcrm.calendar.repository;

import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.calendar.dto.ServiceItemCalendarRow;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Read-only, calendar-owned query surface over {@code booking_service_items}. Flight / hotel / visa
 * events are derived best-effort from the free-text {@code service_type} + generic {@code service_date}
 * of service lines (there is no typed per-leg model). The join to {@code Booking} carries the customer
 * / destination snapshots for display; results come back as a flat projection (no entities loaded).
 */
public interface CalendarServiceItemQueryRepository extends Repository<BookingServiceItem, Long> {

    /**
     * Service lines in [from, to] whose lowercased {@code serviceType} is one of {@code types}
     * (e.g. {@code "flight"}, {@code "hotel"}, {@code "visa"}), joined to their booking.
     */
    @Query("""
            SELECT new com.crm.travelcrm.calendar.dto.ServiceItemCalendarRow(
                si.publicId, si.serviceType, si.title, si.serviceDate, si.endDate, si.status,
                b.publicId, b.customerNameSnapshot, b.destinationSnapshot)
            FROM BookingServiceItem si, Booking b
            WHERE b.id = si.bookingId
              AND si.tenantId = :tenantId
              AND si.deletedAt IS NULL AND b.deletedAt IS NULL
              AND si.serviceDate IS NOT NULL
              AND si.serviceDate >= :from AND si.serviceDate <= :to
              AND LOWER(si.serviceType) IN :types
            """)
    List<ServiceItemCalendarRow> findServiceEvents(@Param("tenantId") Long tenantId,
                                                   @Param("from") LocalDate from,
                                                   @Param("to") LocalDate to,
                                                   @Param("types") Collection<String> types);
}