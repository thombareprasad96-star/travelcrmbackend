package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommConversation;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.enums.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations.
 *
 * <p><b>Every finder here is tenant-scoped by name.</b> {@code findById} / {@code getReferenceById}
 * and friends are banned on tenant entities by {@code TenantIsolationArchTest} and would be a silent
 * cross-tenant read: Hibernate's {@code tenantFilter} is enabled only on {@code @Transactional}
 * methods and never applies to {@code EntityManager.find}.
 *
 * <p>The counting queries below are what the hub's four headline tiles read. They are deliberately
 * COUNTs rather than {@code list().size()} — the inbox of a working agency is the largest list in
 * the product, and the tiles are rendered on every page load.
 */
public interface CommConversationRepository
        extends JpaRepository<CommConversation, Long>, JpaSpecificationExecutor<CommConversation> {

    Optional<CommConversation> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** By internal id, tenant-scoped — {@code findById} is banned on tenant entities. */
    Optional<CommConversation> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /** Batch variant for resolving the threads behind a page of search hits. */
    List<CommConversation> findByTenantIdAndIdInAndDeletedAtIsNull(Long tenantId, Collection<Long> ids);

    /**
     * The open thread for a contact on a channel, if one exists.
     *
     * <p>Looks for a NON-closed thread on purpose: a customer writing again six months after a
     * closed enquiry starts a fresh conversation rather than resurrecting a settled one, which
     * mirrors how {@code LeadIngestService} appends only to non-terminal leads.
     */
    Optional<CommConversation> findFirstByTenantIdAndContactIdentityIdAndChannelAndStatusNotAndDeletedAtIsNullOrderByLastMessageAtDesc(
            Long tenantId, Long contactIdentityId, CommChannel channel, ConversationStatus excludedStatus);

    List<CommConversation> findByTenantIdAndContactIdentityIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
            Long tenantId, Long contactIdentityId);

    // ── Hub tiles ────────────────────────────────────────────────────────────────────────────

    /**
     * Per-channel split for the hub's channel tabs and the analytics donut.
     *
     * <p>The unread and pending-reply tiles are NOT here: they are
     * {@code count(Specification)} calls over the very same predicate the list endpoint builds, so
     * the tile and the list it links to can never disagree about what the caller may see. This one
     * needs a {@code GROUP BY} that a Specification cannot express, so it repeats the scope
     * predicate by hand.
     *
     * <p>{@code scopeAll} exists because JPQL cannot bind a null collection: when the caller's scope
     * is ALL the assignee clause short-circuits and {@code assignees} is ignored (callers still pass
     * a non-empty dummy). An unassigned thread is visible at every scope above NONE — a message from
     * a new number belongs to nobody yet, and hiding it until someone claims it means nobody does.
     *
     * <p>Rows are {@code [CommChannel, Long]}.
     */
    @Query("""
            SELECT c.channel, COUNT(c) FROM CommConversation c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL
              AND c.kind = :kind
              AND (:ownerFilter IS NULL OR c.ownerUserId = :ownerFilter)
              AND (:scopeAll = TRUE OR c.assignedUserId IS NULL OR c.assignedUserId IN :assignees)
            GROUP BY c.channel
            """)
    List<Object[]> countByChannel(@Param("tenantId") Long tenantId,
                                  @Param("kind") ConversationKind kind,
                                  @Param("ownerFilter") Long ownerFilter,
                                  @Param("scopeAll") boolean scopeAll,
                                  @Param("assignees") Collection<Long> assignees);

    long countByTenantIdAndKindAndStatusInAndDeletedAtIsNull(
            Long tenantId, ConversationKind kind, Collection<ConversationStatus> statuses);

    // ── Internal chat ────────────────────────────────────────────────────────────────────────

    /** Internal channels the given user belongs to, newest activity first. */
    @Query("""
            SELECT c FROM CommConversation c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL
              AND c.kind = com.crm.travelcrm.communication.enums.ConversationKind.INTERNAL
              AND EXISTS (SELECT 1 FROM CommConversationMember m
                          WHERE m.conversationId = c.id AND m.userId = :userId AND m.deletedAt IS NULL)
            ORDER BY c.lastMessageAt DESC
            """)
    List<CommConversation> findInternalForMember(@Param("tenantId") Long tenantId,
                                                 @Param("userId") Long userId);

    // ── Housekeeping ─────────────────────────────────────────────────────────────────────────

    /** Snoozed threads whose timer has expired — flipped back to OPEN by the scheduler. */
    List<CommConversation> findByTenantIdAndStatusAndSnoozedUntilBeforeAndDeletedAtIsNull(
            Long tenantId, ConversationStatus status, LocalDateTime cutoff);
}
