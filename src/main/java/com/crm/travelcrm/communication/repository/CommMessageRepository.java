package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Messages — the timeline, and the full-text search behind the global search bar.
 *
 * <h2>Two rules that are easy to break here</h2>
 * <ol>
 *   <li><b>Tenant scope is by hand in every native query.</b> The Hibernate {@code tenantFilter}
 *       rewrites JPQL and derived queries only; it does nothing to native SQL. Neither does
 *       {@code TenantIsolationArchTest}, which is a ban on five method names, not an analysis of
 *       query text. A native query missing {@code tenant_id = :tenantId} compiles, passes every
 *       test, and reads the whole platform.</li>
 *   <li><b>Private notes are excluded in the QUERY, never in a mapper.</b> Filtering after the fetch
 *       means the rows existed in memory in a request that was not entitled to them, and one
 *       forgotten {@code .filter()} on a new endpoint leaks them.</li>
 * </ol>
 */
public interface CommMessageRepository
        extends JpaRepository<CommMessage, Long>, JpaSpecificationExecutor<CommMessage> {

    Optional<CommMessage> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * One page of a thread, newest first, with private notes the caller may not see already removed.
     *
     * @param includePrivate true when the caller holds {@code COMM_NOTE_PRIVATE_READ}
     * @param viewerUserId   the caller — always sees their OWN private notes
     */
    @Query("""
            SELECT m FROM CommMessage m
            WHERE m.tenantId = :tenantId AND m.deletedAt IS NULL
              AND m.conversationId = :conversationId
              AND (m.visibility <> com.crm.travelcrm.communication.enums.MessageVisibility.PRIVATE
                   OR :includePrivate = TRUE
                   OR m.senderUserId = :viewerUserId)
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    Page<CommMessage> findTimeline(@Param("tenantId") Long tenantId,
                                   @Param("conversationId") Long conversationId,
                                   @Param("includePrivate") boolean includePrivate,
                                   @Param("viewerUserId") Long viewerUserId,
                                   Pageable pageable);

    /**
     * Full-text search over message bodies.
     *
     * <p>Runs against {@code search_tsv} — a Postgres GENERATED column with a GIN index, declared in
     * V2 PART 17 and deliberately not mapped on the entity. {@code plainto_tsquery} is used rather
     * than {@code to_tsquery} because the input is whatever a user typed into a search box; the
     * latter throws a syntax error on an unbalanced quote and would surface as a 500.
     *
     * <p>The {@code 'simple'} configuration is intentional: message bodies here are routinely
     * Hinglish and transliterated Devanagari, where English stemming actively harms recall.
     *
     * <p>{@code ORDER BY} lives in the SQL, so callers must pass an UNSORTED {@code Pageable} —
     * Spring Data would otherwise append a second ORDER BY and the statement would not parse.
     */
    @Query(value = """
            SELECT m.* FROM comm_messages m
            WHERE m.tenant_id = :tenantId
              AND m.deleted_at IS NULL
              AND m.search_tsv @@ plainto_tsquery('simple', :q)
              AND (m.visibility <> 'PRIVATE'
                   OR CAST(:includePrivate AS boolean) = TRUE
                   OR m.sender_user_id = :viewerUserId)
              AND (CAST(:ownerFilter AS bigint) IS NULL OR m.owner_user_id = :ownerFilter)
            ORDER BY m.occurred_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM comm_messages m
            WHERE m.tenant_id = :tenantId
              AND m.deleted_at IS NULL
              AND m.search_tsv @@ plainto_tsquery('simple', :q)
              AND (m.visibility <> 'PRIVATE'
                   OR CAST(:includePrivate AS boolean) = TRUE
                   OR m.sender_user_id = :viewerUserId)
              AND (CAST(:ownerFilter AS bigint) IS NULL OR m.owner_user_id = :ownerFilter)
            """,
            nativeQuery = true)
    Page<CommMessage> searchFullText(@Param("tenantId") Long tenantId,
                                     @Param("q") String q,
                                     @Param("includePrivate") boolean includePrivate,
                                     @Param("viewerUserId") Long viewerUserId,
                                     @Param("ownerFilter") Long ownerFilter,
                                     Pageable pageable);

    /**
     * Idempotency probe for an inbound delivery.
     *
     * <p>Backed by {@code uq_msg_provider_id}, unique per tenant. A provider retry — Interakt's
     * response SLA is about three seconds, so a slow reply IS a retry — must append nothing.
     */
    Optional<CommMessage> findByTenantIdAndProviderMessageIdAndDeletedAtIsNull(
            Long tenantId, String providerMessageId);

    /** Newest message in a thread — used to recompute the conversation's denormalised summary. */
    Optional<CommMessage> findFirstByTenantIdAndConversationIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(
            Long tenantId, Long conversationId);

    long countByTenantIdAndConversationIdAndDeletedAtIsNull(Long tenantId, Long conversationId);

    /** Unread count for an internal member: everything after their {@code lastReadAt}. */
    @Query("""
            SELECT COUNT(m) FROM CommMessage m
            WHERE m.tenantId = :tenantId AND m.deletedAt IS NULL
              AND m.conversationId = :conversationId
              AND m.senderUserId <> :viewerUserId
              AND (:since IS NULL OR m.occurredAt > :since)
            """)
    long countUnreadSince(@Param("tenantId") Long tenantId,
                          @Param("conversationId") Long conversationId,
                          @Param("viewerUserId") Long viewerUserId,
                          @Param("since") LocalDateTime since);

    /** Recent activity feed for the hub, across every thread the caller may see. */
    @Query("""
            SELECT m FROM CommMessage m
            WHERE m.tenantId = :tenantId AND m.deletedAt IS NULL
              AND m.visibility = com.crm.travelcrm.communication.enums.MessageVisibility.PUBLIC
              AND (:ownerFilter IS NULL OR m.ownerUserId = :ownerFilter)
            ORDER BY m.occurredAt DESC, m.id DESC
            """)
    List<CommMessage> findRecentActivity(@Param("tenantId") Long tenantId,
                                         @Param("ownerFilter") Long ownerFilter,
                                         Pageable pageable);
}
