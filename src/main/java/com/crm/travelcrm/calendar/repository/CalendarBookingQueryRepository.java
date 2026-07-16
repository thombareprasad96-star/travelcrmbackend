package com.crm.travelcrm.calendar.repository;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.calendar.dto.SumCount;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Read-only, calendar-owned query surface over {@code bookings}. Declared in the calendar package
 * (a narrow {@link Repository}) so the booking module is not modified. Every query is explicitly
 * tenant-scoped and excludes soft-deleted rows.
 */
public interface CalendarBookingQueryRepository extends Repository<Booking, Long> {

    /**
     * Bookings whose trip (travel) date falls in [from, to], for the given statuses — the source for
     * both TRIP events and the derive-from-travel-date PAYMENT_DUE events.
     */
    List<Booking> findByTenantIdAndStatusInAndTravelDateBetweenAndDeletedAtIsNull(
            Long tenantId, Collection<BookingStatus> statuses, LocalDate from, LocalDate to);

    /** KPI: upcoming/ongoing confirmed trips (travel date today or later). */
    long countByTenantIdAndStatusAndTravelDateGreaterThanEqualAndDeletedAtIsNull(
            Long tenantId, BookingStatus status, LocalDate onOrAfter);

    /** KPI: revenue booked in [from, to) (by booking date), excluding cancelled/refunded. */
    @Query("""
            SELECT SUM(b.totalPayable) FROM Booking b
            WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL
              AND b.status NOT IN :excluded
              AND b.bookingDate >= :from AND b.bookingDate < :to
            """)
    BigDecimal sumRevenueBetween(@Param("tenantId") Long tenantId,
                                 @Param("excluded") Collection<BookingStatus> excluded,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /** KPI: total outstanding across all live, non-cancelled/refunded bookings with a balance. */
    @Query("""
            SELECT new com.crm.travelcrm.calendar.dto.SumCount(
                SUM(b.totalPayable - b.paidAmount), COUNT(b))
            FROM Booking b
            WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL
              AND b.status NOT IN :excluded
              AND b.totalPayable > b.paidAmount
            """)
    SumCount sumOutstanding(@Param("tenantId") Long tenantId,
                            @Param("excluded") Collection<BookingStatus> excluded);

    /** Payment-due summary: balance owing on bookings whose travel date is already past. */
    @Query("""
            SELECT new com.crm.travelcrm.calendar.dto.SumCount(
                SUM(b.totalPayable - b.paidAmount), COUNT(b))
            FROM Booking b
            WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL
              AND b.status NOT IN :excluded
              AND b.totalPayable > b.paidAmount
              AND b.travelDate < :today
            """)
    SumCount sumOverdueBefore(@Param("tenantId") Long tenantId,
                              @Param("excluded") Collection<BookingStatus> excluded,
                              @Param("today") LocalDate today);

    /** Payment-due summary: balance owing on bookings whose travel date is the reference date. */
    @Query("""
            SELECT new com.crm.travelcrm.calendar.dto.SumCount(
                SUM(b.totalPayable - b.paidAmount), COUNT(b))
            FROM Booking b
            WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL
              AND b.status NOT IN :excluded
              AND b.totalPayable > b.paidAmount
              AND b.travelDate = :today
            """)
    SumCount sumDueOn(@Param("tenantId") Long tenantId,
                      @Param("excluded") Collection<BookingStatus> excluded,
                      @Param("today") LocalDate today);
}
