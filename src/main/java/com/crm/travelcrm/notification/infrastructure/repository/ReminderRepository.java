package com.crm.travelcrm.notification.infrastructure.repository;

import com.crm.travelcrm.notification.domain.entity.Reminder;
import com.crm.travelcrm.notification.domain.enums.ReminderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    Page<Reminder> findAllByOwnerUserIdAndStatusNotAndDeletedAtIsNullOrderByRemindAtAsc(
            Long ownerUserId, ReminderStatus excludeStatus, Pageable pageable);

    Optional<Reminder> findByPublicIdAndOwnerUserIdAndDeletedAtIsNull(
            UUID publicId, Long ownerUserId);

    Optional<Reminder> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /**
     * Claims due reminders atomically using {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>Runs with the Hibernate tenant filter <em>disabled</em> (scheduler thread has
     * no TenantContext) — this is intentional: we need to see all tenants' due reminders.
     * Tenant isolation is enforced per-row by the caller setting
     * {@code TenantContext.setTenantId(reminder.getTenantId())} before processing each row.
     *
     * <p>Must be called inside a {@code @Transactional} method. The lock is released when
     * that transaction commits, at which point the rows are already marked {@code PROCESSING}
     * and therefore invisible to other instances' queries (which filter on {@code PENDING/SNOOZED}).
     */
    @Query(value = """
            SELECT r.* FROM reminders r
            WHERE r.deleted_at IS NULL
              AND (
                    (r.status = 'PENDING'  AND r.remind_at    <= :now)
                 OR (r.status = 'SNOOZED' AND r.snoozed_until <= :now)
              )
            ORDER BY r.remind_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Reminder> findDueAndLock(@Param("now") Instant now,
                                  @Param("batchSize") int batchSize);
}