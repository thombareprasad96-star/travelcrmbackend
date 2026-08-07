package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommScheduledMessage;
import com.crm.travelcrm.communication.enums.ScheduledMessageStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One-off scheduled sends. Recurring/triggered sends stay in {@code marketing/} — see the entity. */
public interface CommScheduledMessageRepository extends JpaRepository<CommScheduledMessage, Long> {

    Optional<CommScheduledMessage> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * The scheduler's poll, chunked by {@code Pageable}.
     *
     * <p>Bounded on purpose: the connection pool is ten wide on a two-vCPU box shared with Postgres,
     * and there is no write-side JDBC batching configured anywhere in this application. An unbounded
     * fetch of a backlog would starve every concurrent request.
     */
    List<CommScheduledMessage> findByTenantIdAndStatusAndSendAtLessThanEqualAndDeletedAtIsNullOrderBySendAtAsc(
            Long tenantId, ScheduledMessageStatus status, Instant cutoff, Pageable pageable);

    List<CommScheduledMessage> findByTenantIdAndStatusAndDeletedAtIsNullOrderBySendAtAsc(
            Long tenantId, ScheduledMessageStatus status);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, ScheduledMessageStatus status);
}
