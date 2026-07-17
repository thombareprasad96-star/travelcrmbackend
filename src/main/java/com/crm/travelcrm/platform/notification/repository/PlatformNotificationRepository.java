package com.crm.travelcrm.platform.notification.repository;

import com.crm.travelcrm.platform.notification.entity.PlatformNotification;
import com.crm.travelcrm.platform.notification.enums.PlatformNotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method is keyed on {@code recipientSuperAdminId}. There is no tenant filter on this entity
 * (by design — see {@link PlatformNotification}), so that predicate is the ONLY thing separating one
 * SuperAdmin's feed from another's. It is not optional on any query, including the bulk update.
 */
public interface PlatformNotificationRepository extends JpaRepository<PlatformNotification, Long> {

    /** All non-deleted notifications for a specific SuperAdmin, newest first. */
    Page<PlatformNotification> findAllByRecipientSuperAdminIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long recipientSuperAdminId, Pageable pageable);

    /** Ownership-guarded fetch by public UUID — prevents cross-SuperAdmin reads. */
    Optional<PlatformNotification> findByPublicIdAndRecipientSuperAdminIdAndDeletedAtIsNull(
            UUID publicId, Long recipientSuperAdminId);

    long countByRecipientSuperAdminIdAndStatusAndDeletedAtIsNull(
            Long recipientSuperAdminId, PlatformNotificationStatus status);

    /**
     * Bulk mark-all-read for one SuperAdmin.
     *
     * <p>{@code @Modifying} JPQL bypasses Hibernate filters entirely — but this entity has none to
     * bypass, and the {@code recipientSuperAdminId} predicate below is written explicitly, so the
     * scope is stated in the query rather than assumed from context.
     */
    @Modifying
    @Query("""
            UPDATE PlatformNotification n
               SET n.status  = 'READ',
                   n.readAt  = CURRENT_TIMESTAMP
             WHERE n.recipientSuperAdminId = :superAdminId
               AND n.status  = 'UNREAD'
               AND n.deletedAt IS NULL
            """)
    int markAllReadForSuperAdmin(@Param("superAdminId") Long superAdminId);
}