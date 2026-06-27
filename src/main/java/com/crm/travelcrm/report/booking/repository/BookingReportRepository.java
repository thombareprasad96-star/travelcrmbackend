package com.crm.travelcrm.report.booking.repository;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Report-owned, read-only queries over {@code Booking} for the Revenue / Travel-date / Intl-Domestic
 * reports. Kept separate from {@code BookingRepository} so the booking module is untouched.
 *
 * <p>The {@code dateType} discriminator selects which date column the range applies to
 * ("Created Date" → {@code createdAt}, "Travel Date" → {@code travelDate}, anything else →
 * {@code bookingDate}); both a {@link LocalDate} and a {@link LocalDateTime} bound are passed so the
 * created-at (timestamp) and the date columns can both be compared.
 */
public interface BookingReportRepository extends Repository<Booking, Long> {

    @Query("""
            SELECT b FROM Booking b
            WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL
              AND ( (:dateType = 'Created Date' AND b.createdAt  BETWEEN :fromDt AND :toDt)
                 OR (:dateType = 'Travel Date'  AND b.travelDate BETWEEN :fromD  AND :toD)
                 OR (:dateType <> 'Created Date' AND :dateType <> 'Travel Date'
                       AND b.bookingDate BETWEEN :fromD AND :toD) )
              AND (:status    IS NULL OR b.status        = :status)
              AND (:payStatus IS NULL OR b.paymentStatus = :payStatus)
              AND (:minAmount IS NULL OR b.customerAmount >= :minAmount)
              AND (:maxAmount IS NULL OR b.customerAmount <= :maxAmount)
              AND (:search    IS NULL OR LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                                      OR LOWER(b.customerNameSnapshot) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            ORDER BY b.bookingDate DESC
            """)
    List<Booking> findRevenueList(
            @Param("tenantId")  Long tenantId,
            @Param("dateType")  String dateType,
            @Param("fromD")     LocalDate fromD,
            @Param("toD")       LocalDate toD,
            @Param("fromDt")    LocalDateTime fromDt,
            @Param("toDt")      LocalDateTime toDt,
            @Param("status")    BookingStatus status,
            @Param("payStatus") PaymentStatus payStatus,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("search")    String search);

    /** Travel-date trends grouped by a Postgres {@code TO_CHAR} format; row = [period, bookings, revenue, sortKey]. */
    @Query(value = """
            SELECT TO_CHAR(b.travel_date, :fmt)        AS period,
                   COUNT(*)                             AS bookings,
                   COALESCE(SUM(b.customer_amount), 0)  AS revenue,
                   MIN(b.travel_date)                   AS sort_key
            FROM bookings b
            WHERE b.tenant_id = :tenantId AND b.deleted_at IS NULL
              AND b.travel_date BETWEEN :fromD AND :toD
              AND (CAST(:status AS text) IS NULL OR b.status = :status)
            GROUP BY 1
            ORDER BY MIN(b.travel_date)
            """, nativeQuery = true)
    List<Object[]> travelTrends(
            @Param("tenantId") Long tenantId,
            @Param("fromD")    LocalDate fromD,
            @Param("toD")      LocalDate toD,
            @Param("fmt")      String fmt,
            @Param("status")   String status);

    /** Top travel dates by booking count; row = [travel_date, bookings]. */
    @Query(value = """
            SELECT b.travel_date AS travel_date, COUNT(*) AS bookings
            FROM bookings b
            WHERE b.tenant_id = :tenantId AND b.deleted_at IS NULL
              AND b.travel_date BETWEEN :fromD AND :toD
              AND (CAST(:status AS text) IS NULL OR b.status = :status)
            GROUP BY b.travel_date
            ORDER BY COUNT(*) DESC
            LIMIT :topN
            """, nativeQuery = true)
    List<Object[]> peakDates(
            @Param("tenantId") Long tenantId,
            @Param("fromD")    LocalDate fromD,
            @Param("toD")      LocalDate toD,
            @Param("status")   String status,
            @Param("topN")     int topN);
}