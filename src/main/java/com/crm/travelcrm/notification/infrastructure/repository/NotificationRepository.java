package com.crm.travelcrm.notification.infrastructure.repository;

import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** All non-deleted notifications for a specific user, newest first. */
    Page<Notification> findAllByRecipientUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long recipientUserId, Pageable pageable);

    /** Unread-only feed. */
    Page<Notification> findAllByRecipientUserIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long recipientUserId, NotificationStatus status, Pageable pageable);

    /** Ownership-guarded fetch by public UUID — prevents cross-user reads. */
    Optional<Notification> findByPublicIdAndRecipientUserIdAndDeletedAtIsNull(
            UUID publicId, Long recipientUserId);

    /** Ownership-guarded fetch by numeric id — prevents cross-user reads. */
    Optional<Notification> findByIdAndRecipientUserIdAndDeletedAtIsNull(
            Long id, Long recipientUserId);

    long countByRecipientUserIdAndStatusAndDeletedAtIsNull(
            Long recipientUserId, NotificationStatus status);

    /** Bulk mark-all-read for the authenticated user within the current tenant session. */
    @Modifying
    @Query("""
            UPDATE Notification n
               SET n.status  = 'READ',
                   n.readAt  = CURRENT_TIMESTAMP
             WHERE n.recipientUserId = :userId
               AND n.status  = 'UNREAD'
               AND n.deletedAt IS NULL
            """)
    int markAllReadForUser(@Param("userId") Long userId);
}