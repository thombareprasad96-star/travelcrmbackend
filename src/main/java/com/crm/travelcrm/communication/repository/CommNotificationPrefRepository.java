package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommNotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Per-user notification toggles.
 *
 * <p>Absence means ENABLED — a missing row is "on". That keeps a new user and a newly invented event
 * type both working with no backfill, and means only explicit opt-outs are ever stored.
 */
public interface CommNotificationPrefRepository extends JpaRepository<CommNotificationPref, Long> {

    List<CommNotificationPref> findByTenantIdAndUserIdAndDeletedAtIsNull(Long tenantId, Long userId);

    Optional<CommNotificationPref> findByTenantIdAndUserIdAndEventTypeAndChannelAndDeletedAtIsNull(
            Long tenantId, Long userId, String eventType, String channel);

    /** The send-time gate: only ever finds a row when the user has explicitly switched something off. */
    List<CommNotificationPref> findByTenantIdAndUserIdInAndEventTypeAndChannelAndEnabledFalseAndDeletedAtIsNull(
            Long tenantId, List<Long> userIds, String eventType, String channel);
}
