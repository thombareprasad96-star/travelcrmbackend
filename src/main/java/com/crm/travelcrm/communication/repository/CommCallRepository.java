package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommCall;
import com.crm.travelcrm.communication.enums.CallDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Logged calls. This module never places a call — rows come from a PBX webhook or manual entry. */
public interface CommCallRepository
        extends JpaRepository<CommCall, Long>, JpaSpecificationExecutor<CommCall> {

    Optional<CommCall> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Batch resolve for a timeline page's call rows — {@code findAllById} is banned here. */
    List<CommCall> findByTenantIdAndIdInAndDeletedAtIsNull(Long tenantId, Collection<Long> ids);

    /** Idempotency probe for a PBX webhook — a provider retry must not log the call twice. */
    Optional<CommCall> findByTenantIdAndProviderCallIdAndDeletedAtIsNull(Long tenantId, String providerCallId);

    long countByTenantIdAndDirectionAndDeletedAtIsNull(Long tenantId, CallDirection direction);

    /** Missed calls in a window — a hub tile, and the "missed calls" figure in analytics. */
    @Query("""
            SELECT COUNT(c) FROM CommCall c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL
              AND c.direction = com.crm.travelcrm.communication.enums.CallDirection.MISSED
              AND (:since IS NULL OR c.startedAt >= :since)
              AND (:ownerFilter IS NULL OR c.ownerUserId = :ownerFilter)
            """)
    long countMissed(@Param("tenantId") Long tenantId,
                     @Param("since") LocalDateTime since,
                     @Param("ownerFilter") Long ownerFilter);

    /**
     * Completed calls and total talk time in a window. Row is {@code [count, totalSeconds]}.
     *
     * <p>"Completed" means answered — {@code answeredAt} is null exactly for a missed call, which is
     * why that is the predicate rather than an outcome value (outcome is an open vocabulary).
     */
    @Query("""
            SELECT COUNT(c), COALESCE(SUM(c.durationSeconds), 0) FROM CommCall c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL
              AND c.answeredAt IS NOT NULL
              AND (:since IS NULL OR c.startedAt >= :since)
              AND (:ownerFilter IS NULL OR c.ownerUserId = :ownerFilter)
            """)
    Object[] completedCallStats(@Param("tenantId") Long tenantId,
                                @Param("since") LocalDateTime since,
                                @Param("ownerFilter") Long ownerFilter);
}
