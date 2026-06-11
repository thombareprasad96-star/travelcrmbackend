package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long>,
        JpaSpecificationExecutor<Booking> {

    // ── Lookup ───────────────────────────────────────────────────────────────

    Optional<Booking> findByPublicIdAndActiveTrue(UUID publicId);

    Optional<Booking> findByBookingCodeAndActiveTrue(String bookingCode);

    Optional<Booking> findByIdAndActiveTrue(Long id);

    Optional<Booking> findTopByOrderByIdDesc();

    List<Booking> findAllByCustomerIdAndActiveTrue(Long customerId);

    boolean existsByBookingCode(String bookingCode);

    // ── Stats queries ────────────────────────────────────────────────────────

    long countByActiveTrue();

    long countByStatusAndActiveTrue(BookingStatus status);

    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumTotalRevenue();

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumTotalCollected();

    @Query("SELECT COALESCE(SUM(b.totalPayable - b.paidAmount), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumTotalPending();

    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b WHERE b.active = true AND b.status = 'REFUNDED'")
    BigDecimal sumTotalRefund();

    @Query("SELECT COALESCE(SUM(b.netProfit), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumNetProfit();

    @Query("SELECT COALESCE(SUM(b.gst), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumGstCollected();

    @Query("SELECT COALESCE(SUM(b.tcs), 0) FROM Booking b WHERE b.active = true")
    BigDecimal sumTcsCollected();
}