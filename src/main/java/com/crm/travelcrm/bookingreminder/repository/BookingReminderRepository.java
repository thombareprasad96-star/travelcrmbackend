package com.crm.travelcrm.bookingreminder.repository;

import com.crm.travelcrm.bookingreminder.entity.BookingReminder;
import com.crm.travelcrm.bookingreminder.entity.BookingReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingReminderRepository extends JpaRepository<BookingReminder, Long> {

    List<BookingReminder> findByTenantIdAndDeletedAtIsNullOrderByReminderDateDesc(Long tenantId);

    List<BookingReminder> findByTenantIdAndStatusAndDeletedAtIsNullOrderByReminderDateDesc(
            Long tenantId, BookingReminderStatus status);

    Optional<BookingReminder> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    List<BookingReminder> findByTenantIdAndBookingCodeAndDeletedAtIsNullOrderByReminderDateDesc(
            Long tenantId, String bookingCode);

    List<BookingReminder> findByTenantIdAndDeletedAtIsNullAndTravelDateBetweenOrderByTravelDateAsc(
            Long tenantId, Instant from, Instant to);

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, BookingReminderStatus status);
}