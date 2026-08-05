package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    // ── List ─────────────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Page<Lead> findAllByTenantIdAndDeletedAtIsNull(
            Long tenantId, Pageable pageable);

    /**
     * Full unpaged fetch for the Kanban board — every live lead of the tenant,
     * newest first, with the assignee eagerly joined to avoid N+1.
     */
    @EntityGraph(attributePaths = "assignedUser")
    List<Lead> findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    @EntityGraph(attributePaths = "assignedUser")
    @Query("""
            SELECT l
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt BETWEEN :from AND :to
            ORDER BY l.createdAt DESC
            """)
    List<Lead> findDashboardLeads(@Param("tenantId") Long tenantId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @EntityGraph(attributePaths = "assignedUser")
    @Query("""
            SELECT l
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.travelDate BETWEEN :from AND :to
            ORDER BY l.travelDate ASC
            """)
    List<Lead> findUpcomingTravelLeads(@Param("tenantId") Long tenantId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    // ── Scope-filtered variants (own / team): owner_id IN (:assignedUserIds) ──
    @EntityGraph(attributePaths = "assignedUser")
    Page<Lead> findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdIn(
            Long tenantId, Collection<Long> assignedUserIds, Pageable pageable);

    @EntityGraph(attributePaths = "assignedUser")
    List<Lead> findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdInOrderByCreatedAtDesc(
            Long tenantId, Collection<Long> assignedUserIds);

    // ── Single fetch ─────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByPublicIdAndTenantIdAndDeletedAtIsNull(
            UUID publicId, Long tenantId);

    /** Batch fetch by internal ids — read-only, used by the Follow-up Report to enrich
     *  reminders with their lead's stage / temperature / travel date without an N+1. */
    List<Lead> findByTenantIdAndIdInAndDeletedAtIsNull(Long tenantId, Collection<Long> ids);

    // ── Search ───────────────────────────────────────────────────────────────
    // Newest-first single fetch. Repeat business now allows several non-deleted leads
    // to share a phone/email (e.g. a CONVERTED one kept for history + a fresh OPEN one),
    // so a plain Optional finder could throw NonUniqueResult — always take the latest.
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findFirstByEmailAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String email, Long tenantId);

    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findFirstByPhoneAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String phone, Long tenantId);

    // ── Existence check (cross-aggregate FK validation, e.g. Booking.leadId) ──
    boolean existsByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /**
     * Every lead this tenant has ever had, INCLUDING soft-deleted ones. Deliberately not
     * deletedAt-aware: the only caller is {@code LeadCodeGenerator}, seeding a brand-new counter
     * row on a tenant whose rows were code-backfilled. A trashed lead still holds a lead_code that
     * was quoted to a customer, so it must be counted or the counter reissues it.
     */
    long countByTenantId(Long tenantId);

    // Tenant-scoped fetch by internal id — used by the cancel-booking flow to revert
    // (REOPENED) or hard-delete the associated lead. Tenant-safe (never bare findById).
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    // ── Duplicate checks (OPEN leads only) ───────────────────────────────────
    // A duplicate is only a conflict while the existing lead is still OPEN. Once it
    // reaches a terminal stage (CONVERTED / LOST) the contact is free to come back as a
    // new lead — so the caller passes the terminal stages to exclude. Mirrors the
    // uq_leads_*_tenant_open partial unique indexes so the service check and the DB agree.
    boolean existsByEmailAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
            String email, Long tenantId, Collection<LeadStage> excludedStages);

    /**
     * The append-on-repeat target (owner decision 1): the tenant's most recent OPEN lead for a
     * canonical phone.
     *
     * <p><b>Reads {@code phoneNormalized}, not the raw phone — and only the MACHINE path may.</b>
     * Inbound channels deliver bare E.164 while human-typed rows carry separators, so matching on the
     * raw phone would match nothing and every repeat caller would silently become a duplicate lead.
     * The human path deliberately still compares raw: two pre-existing open leads that canonicalise
     * identically are legal today, and moving the human dedup onto this column would make BOTH of them
     * permanently un-editable. The machine path has no such legacy — it has only ever written the
     * canonical column.
     *
     * <p>Ordered newest-first and takes the first: the partial unique index is not yet on the
     * canonical column, so more than one match is possible in principle. Appending to the most recent
     * open lead is the behaviour a human would expect.
     */
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullAndLeadStageNotInOrderByCreatedAtDesc(
            String phoneNormalized, Long tenantId, Collection<LeadStage> excludedStages);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
            String phone, Long tenantId, Collection<LeadStage> excludedStages);

    // ── Duplicate check excluding self (for update) ──────────────────────────
    boolean existsByEmailAndTenantIdAndDeletedAtIsNullAndLeadStageNotInAndPublicIdNot(
            String email, Long tenantId, Collection<LeadStage> excludedStages, UUID publicId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndLeadStageNotInAndPublicIdNot(
            String phone, Long tenantId, Collection<LeadStage> excludedStages, UUID publicId);

    // ── Trashed-only duplicate lookup (create-time "restore available" detection) ──
    // An ACTIVE duplicate is a hard "already exists" error; a match that lives ONLY in Trash
    // returns the trashed record so the API can offer Restore instead. Intercepts before insert,
    // so the DB unique constraint (uk_lead_tenant_email / uk_lead_tenant_phone) never throws a
    // raw error. Newest-first in case the same email/phone was trashed more than once.
    Optional<Lead> findFirstByEmailAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String email, Long tenantId);

    Optional<Lead> findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String phone, Long tenantId);

    // ── Statistics ────────────────────────────────────────────────────────────
    // All aggregation runs in the database. Never load leads into memory to
    // count them, and never traverse a User→leads collection.

    /** Total live leads in the tenant — for the plan lead-cap check. */
    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    /** Total live leads assigned to one user (tenant-scoped). */
    long countByAssignedUserPublicIdAndTenantIdAndDeletedAtIsNull(
            UUID userPublicId, Long tenantId);

    // ── Claim window: the incoming-leads feed and its tiles ──────────────────

    /**
     * Every lead in the tenant still open to claim, newest first — the incoming-leads list.
     *
     * <p>The predicate is the SQL twin of {@code LeadAccessGuard.isOpenToClaim} and of the partial
     * index {@code idx_leads_open_to_claim}; all three must state the same thing or the index stops
     * being used and the feed stops agreeing with the per-lead guard.
     *
     * <p><b>No owner filter.</b> An open lead is visible tenant-wide by design (the approved
     * claim-window widening) — that is what lets anyone claim it. Row-scope resumes the moment it
     * locks, at which point it leaves this feed entirely.
     */
    @EntityGraph(attributePaths = "assignedUser")
    @Query("""
            SELECT l
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.firstContactedAt IS NULL
              AND l.leadStage NOT IN :terminalStages
            ORDER BY l.createdAt DESC
            """)
    List<Lead> findOpenToClaim(@Param("tenantId") Long tenantId,
                               @Param("terminalStages") Collection<LeadStage> terminalStages,
                               Pageable pageable);

    /** Tile 2 — how many leads are claimable right now (not scoped to today; see the DTO). */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.firstContactedAt IS NULL
              AND l.leadStage NOT IN :terminalStages
            """)
    long countOpenToClaim(@Param("tenantId") Long tenantId,
                          @Param("terminalStages") Collection<LeadStage> terminalStages);

    /** Tile 1 — leads created inside the tenant's local day. */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt >= :from AND l.createdAt < :to
            """)
    long countCreatedBetween(@Param("tenantId") Long tenantId,
                             @Param("from") LocalDateTime from,
                             @Param("to") LocalDateTime to);

    /**
     * Tile 3 — mean first-response seconds over leads CONTACTED in the window.
     *
     * @return null when nobody was contacted in the window; the caller renders "—" rather than 0,
     *         because "no data" and "instant response" are opposite facts
     */
    @Query("""
            SELECT AVG(l.firstResponseSeconds)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.firstResponseSeconds IS NOT NULL
              AND l.firstContactedAt >= :from AND l.firstContactedAt < :to
            """)
    Double avgFirstResponseSeconds(@Param("tenantId") Long tenantId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /**
     * Tile 4 — SLA breaches among leads created in the window.
     *
     * <p>Two disjoint populations in one count, and both are needed:
     * <ol>
     *   <li>answered, but slower than the lead's own pinned target;</li>
     *   <li>never answered, still active, and already past that target as of {@code now}.</li>
     * </ol>
     * The second is why this is computed live. {@code COALESCE(slaTargetSeconds, :defaultTarget)}
     * covers rows written before the column existed, which otherwise compare against NULL and
     * silently never breach.
     *
     * <p><b>Both branches compare against the lead's OWN pinned target</b>, via
     * {@code timestampdiff} rather than a single precomputed cutoff timestamp. A shared cutoff
     * would be right only while every lead in the window happened to share one target — true today,
     * false the moment the configured target is changed mid-day or a per-tenant target lands, and
     * wrong silently in exactly the direction that under-reports breaches.
     */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.createdAt >= :from AND l.createdAt < :to
              AND (
                    (l.firstResponseSeconds IS NOT NULL
                      AND l.firstResponseSeconds > COALESCE(l.slaTargetSeconds, :defaultTarget))
                 OR (l.firstContactedAt IS NULL
                      AND l.leadStage NOT IN :terminalStages
                      AND timestampdiff(SECOND, l.createdAt, :now)
                            > COALESCE(l.slaTargetSeconds, :defaultTarget))
              )
            """)
    long countSlaBreaches(@Param("tenantId") Long tenantId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          @Param("now") LocalDateTime now,
                          @Param("defaultTarget") int defaultTarget,
                          @Param("terminalStages") Collection<LeadStage> terminalStages);

    // ── Claim window: compare-and-swap ownership transitions ─────────────────
    //
    // All three statements below are SINGLE atomic UPDATEs whose WHERE clause carries the entire
    // precondition. That is the whole concurrency design: two simultaneous claims cannot both
    // succeed because the second one's `claimVersion = :expectedVersion` no longer matches. The
    // loser gets 0 rows and is told who won — it is never silently overwritten, and there is never
    // a moment where the row has two owners.
    //
    // Read-then-write in Java (load, check, save) would NOT be equivalent: between the read and the
    // write another transaction commits, and both writers see a valid precondition. That is exactly
    // the bug this shape exists to make unrepresentable.
    //
    // COALESCE(l.claimVersion, 0) throughout: ddl-auto=update adds the column NULL on existing rows,
    // and `NULL = 0` is NULL (not false) in SQL, so an un-coalesced comparison would make every
    // pre-existing lead permanently unclaimable until the backfill ran. It runs in db/indexes.sql,
    // but correctness must not depend on that having happened.
    //
    // @Modifying(clearAutomatically, flushAutomatically): the caller holds the same Lead in the
    // persistence context and re-reads it immediately afterwards. Without these, the bulk UPDATE
    // bypasses the first-level cache and the re-read returns the STALE entity — the claim would
    // succeed in the database and the response would report the old owner.

    /**
     * Take ownership of an OPEN lead. Succeeds only while the claim window is open and nobody has
     * moved since the caller read {@code expectedVersion}.
     *
     * @return 1 when the claim was won, 0 when it was lost (stale version, already contacted,
     *         terminal stage, deleted, or wrong tenant) — the caller must re-read to say WHY
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lead l
               SET l.assignedUser  = :newOwner,
                   l.claimVersion  = COALESCE(l.claimVersion, 0) + 1
             WHERE l.id              = :leadId
               AND l.tenantId        = :tenantId
               AND l.deletedAt      IS NULL
               AND l.firstContactedAt IS NULL
               AND l.leadStage   NOT IN :terminalStages
               AND COALESCE(l.claimVersion, 0) = :expectedVersion
            """)
    int claimLead(@Param("leadId") Long leadId,
                  @Param("tenantId") Long tenantId,
                  @Param("newOwner") User newOwner,
                  @Param("expectedVersion") int expectedVersion,
                  @Param("terminalStages") Collection<LeadStage> terminalStages);

    /**
     * Close the claim window: stamp first contact and freeze the SLA clock.
     *
     * <p>No {@code expectedVersion} guard, on purpose. Marking contacted is idempotent in intent —
     * whoever gets there first wins and the second click is a no-op, which is the correct behaviour
     * for a button two people may press at once. The {@code firstContactedAt IS NULL} predicate is
     * the guard that matters: it makes the stamp physically unrepeatable, so two concurrent calls
     * can never write two different first-response times.
     *
     * <p>{@code leadStage} is set here too, in the same statement, so the lock and the stage can
     * never disagree — a lead that is locked but still displays "New Lead" is a support ticket.
     *
     * @return 1 when this call closed the window, 0 when it was already closed or the lead is gone
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lead l
               SET l.firstContactedAt       = :contactedAt,
                   l.firstContactedByUserId = :contactedByUserId,
                   l.firstResponseSeconds   = :responseSeconds,
                   l.leadStage              = :contactedStage,
                   l.claimVersion           = COALESCE(l.claimVersion, 0) + 1
             WHERE l.id                = :leadId
               AND l.tenantId          = :tenantId
               AND l.deletedAt        IS NULL
               AND l.firstContactedAt IS NULL
            """)
    int markContacted(@Param("leadId") Long leadId,
                      @Param("tenantId") Long tenantId,
                      @Param("contactedAt") LocalDateTime contactedAt,
                      @Param("contactedByUserId") Long contactedByUserId,
                      @Param("responseSeconds") Long responseSeconds,
                      @Param("contactedStage") LeadStage contactedStage);

    /**
     * Reopen the claim window on a locked lead (a manager undoing a mis-click).
     *
     * <p>{@code firstResponseSeconds} is deliberately left ALONE while the timestamp is cleared.
     * Clearing both would let anyone erase a missed SLA by reopening and re-contacting; keeping the
     * measurement while releasing the lock is what makes the undo safe to grant. The next contact
     * re-stamps {@code firstContactedAt} but {@link #markContactedPreservingSla} keeps the original
     * measurement, so the reported first-response time is always the true one.
     *
     * @return 1 when the window was reopened, 0 when the lead was not locked to begin with
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lead l
               SET l.firstContactedAt       = NULL,
                   l.firstContactedByUserId = NULL,
                   l.leadStage              = :reopenStage,
                   l.claimVersion           = COALESCE(l.claimVersion, 0) + 1
             WHERE l.id                 = :leadId
               AND l.tenantId           = :tenantId
               AND l.deletedAt         IS NULL
               AND l.firstContactedAt IS NOT NULL
               AND l.leadStage      NOT IN :terminalStages
            """)
    int reopenClaimWindow(@Param("leadId") Long leadId,
                          @Param("tenantId") Long tenantId,
                          @Param("reopenStage") LeadStage reopenStage,
                          @Param("terminalStages") Collection<LeadStage> terminalStages);

    /**
     * Re-stamp first contact on a lead whose window was reopened, WITHOUT touching the frozen
     * {@code firstResponseSeconds}. Split from {@link #markContacted} rather than made conditional:
     * one statement that sometimes preserves and sometimes overwrites the SLA measurement is the
     * kind of thing that reads as correct and is not.
     *
     * @return 1 when this call closed the window, 0 when it was already closed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lead l
               SET l.firstContactedAt       = :contactedAt,
                   l.firstContactedByUserId = :contactedByUserId,
                   l.leadStage              = :contactedStage,
                   l.claimVersion           = COALESCE(l.claimVersion, 0) + 1
             WHERE l.id                = :leadId
               AND l.tenantId          = :tenantId
               AND l.deletedAt        IS NULL
               AND l.firstContactedAt IS NULL
            """)
    int markContactedPreservingSla(@Param("leadId") Long leadId,
                                   @Param("tenantId") Long tenantId,
                                   @Param("contactedAt") LocalDateTime contactedAt,
                                   @Param("contactedByUserId") Long contactedByUserId,
                                   @Param("contactedStage") LeadStage contactedStage);

    /**
     * Move a LOCKED lead to a different owner (manager/admin reassignment after first contact).
     * Requires the lead to BE locked — an open lead is claimed, not reassigned, and routing both
     * through one endpoint would let a reassign silently bypass the claim CAS.
     *
     * @return 1 on success, 0 when the lead is open, terminal, deleted or in another tenant
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Lead l
               SET l.assignedUser = :newOwner,
                   l.claimVersion = COALESCE(l.claimVersion, 0) + 1
             WHERE l.id                 = :leadId
               AND l.tenantId           = :tenantId
               AND l.deletedAt         IS NULL
               AND l.firstContactedAt IS NOT NULL
               AND l.leadStage      NOT IN :terminalStages
            """)
    int reassignLockedLead(@Param("leadId") Long leadId,
                           @Param("tenantId") Long tenantId,
                           @Param("newOwner") User newOwner,
                           @Param("terminalStages") Collection<LeadStage> terminalStages);

    /**
     * ACTIVE-lead count per candidate user — the load input for load-based assignment. One GROUP BY
     * query (never a per-user loop), aggregated in the database. Explicitly tenant-scoped (the
     * Hibernate {@code tenantFilter} is not relied upon — {@code User} carries no filter and the
     * filter is only enabled inside a transaction) and deletedAt-aware.
     *
     * <p><b>Zero-count users are absent</b> from the result (GROUP BY only returns users with ≥1
     * active lead) — the caller must zero-fill every candidate before use, so a user with no active
     * leads is a genuine (and most-attractive) candidate. Each row is {@code [userId (Long),
     * count (Long)]}. Pass {@code activeStages = LeadStageGroups.ACTIVE_STAGES}.
     */
    @Query("""
            SELECT l.assignedUser.id, COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage IN :activeStages
            GROUP BY l.assignedUser.id
            """)
    List<Object[]> countActiveLeadsPerUser(@Param("tenantId") Long tenantId,
                                           @Param("userIds") Collection<Long> userIds,
                                           @Param("activeStages") Collection<LeadStage> activeStages);

    /**
     * Per-user ACTIVE-lead count for the team-workload view (the Task workload). One tenant-scoped
     * GROUP BY (no N+1); an INNER JOIN so ONLY users that currently own ≥1 active lead are returned —
     * a caller merges these into its per-user list (adding lead-only users) and leaves everyone else
     * at zero. Each row is
     * {@code [userId (Long), userPublicId (UUID), userName (String), activeLeadCount (Long)]}.
     * Pass {@code activeStages = LeadStageGroups.ACTIVE_STAGES}.
     *
     * <p>The internal {@code id} is selected alongside the publicId so the caller can key the
     * <em>other</em> per-user workload queries (which take internal ids) for a user who owns leads but
     * has no tasks. It never leaves the service — the DTO exposes the publicId.
     */
    @Query("""
            SELECT u.id, u.publicId, u.name, COUNT(l)
            FROM User u
            JOIN Lead l
                   ON l.assignedUser = u
                  AND l.deletedAt IS NULL
                  AND l.leadStage IN :activeStages
            WHERE u.tenantId = :tenantId
              AND u.deletedAt IS NULL
            GROUP BY u.id, u.publicId, u.name
            """)
    List<Object[]> findActiveLeadWorkloadPerUser(@Param("tenantId") Long tenantId,
                                                 @Param("activeStages") Collection<LeadStage> activeStages);

    /**
     * Workload dashboard: every active user of the tenant with their lead
     * count. LEFT JOIN from User so members with zero leads still appear.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserWorkloadDto(
                u.publicId, u.name, u.email, COUNT(l))
            FROM User u
            LEFT JOIN Lead l
                   ON l.assignedUser = u
                  AND l.deletedAt IS NULL
            WHERE u.tenantId = :tenantId
              AND u.isActive = true
              AND u.deletedAt IS NULL
            GROUP BY u.publicId, u.name, u.email
            ORDER BY COUNT(l) DESC, u.name ASC
            """)
    List<UserWorkloadDto> findUserWorkload(@Param("tenantId") Long tenantId);

    /**
     * Scope-filtered workload: same as {@link #findUserWorkload} but limited to the given
     * owner ids (the caller's own/team-visible users). Used when the requester's LEAD_READ
     * scope is not ALL, so an own/team-scoped user can't enumerate the whole tenant.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserWorkloadDto(
                u.publicId, u.name, u.email, COUNT(l))
            FROM User u
            LEFT JOIN Lead l
                   ON l.assignedUser = u
                  AND l.deletedAt IS NULL
            WHERE u.tenantId = :tenantId
              AND u.isActive = true
              AND u.deletedAt IS NULL
              AND u.id IN :userIds
            GROUP BY u.publicId, u.name, u.email
            ORDER BY COUNT(l) DESC, u.name ASC
            """)
    List<UserWorkloadDto> findUserWorkloadForUsers(@Param("tenantId") Long tenantId,
                                                   @Param("userIds") Collection<Long> userIds);

    /** Lead count per (user, stage) pair — feeds the per-user pipeline view. */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserLeadStageCountDto(
                u.publicId, u.name, l.leadStage, COUNT(l))
            FROM Lead l
            JOIN l.assignedUser u
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
            GROUP BY u.publicId, u.name, l.leadStage
            ORDER BY u.name ASC, l.leadStage ASC
            """)
    List<UserLeadStageCountDto> countLeadsByStagePerUser(@Param("tenantId") Long tenantId);

    /**
     * Scope-filtered (user, stage) breakdown — same as {@link #countLeadsByStagePerUser} but
     * limited to the caller's own/team-visible owner ids.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserLeadStageCountDto(
                u.publicId, u.name, l.leadStage, COUNT(l))
            FROM Lead l
            JOIN l.assignedUser u
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND u.id IN :userIds
            GROUP BY u.publicId, u.name, l.leadStage
            ORDER BY u.name ASC, l.leadStage ASC
            """)
    List<UserLeadStageCountDto> countLeadsByStagePerUserForUsers(@Param("tenantId") Long tenantId,
                                                                 @Param("userIds") Collection<Long> userIds);

    /**
     * Per-lead activity roll-up for the list: {@code [leadId, logCount, lastActivityAt]}.
     *
     * <p>The {@code MAX(createdAt)} rides on the GROUP BY that was already running for the count, so
     * "when did we last touch this lead" costs nothing extra. It is what the leads list needs to say
     * a lead is going cold — until now the client could only tell whether a lead had EVER been
     * contacted ({@code firstContactedAt}, {@code logCount > 0}), never when, so a lead last spoken
     * to nine days ago looked identical to one called this morning.
     *
     * <p>Deleted logs are excluded, so removing the only log correctly takes the timestamp with it.
     */
    @Query("""
            SELECT l.lead.id, COUNT(l), MAX(l.createdAt)
            FROM LeadLog l
            WHERE l.lead.id IN :leadIds
              AND l.deletedAt IS NULL
            GROUP BY l.lead.id
            """)
    List<Object[]> countLogsByLeadIds(@Param("leadIds") Collection<Long> leadIds);

    // ── All-Leads dashboard summary ──────────────────────────────────────────
    //
    // Everything below comes in PAIRS: a tenant-wide form and a `...ForUsers` form limited to the
    // caller's visible owner ids. That is the same shape findUserWorkload/findUserWorkloadForUsers
    // already uses, and it exists because ScopeResolver answers with three different things — null
    // (ALL, skip the owner predicate), an empty set (NONE, return nothing), or a set of owner ids.
    // A single query with `IN :userIds` cannot express the ALL case without enumerating every user
    // in the tenant, so the predicate is added or omitted by picking the method, not by passing a
    // flag.
    //
    // These are all aggregates: they run in the database and never load a Lead. The point of the
    // whole block is that the dashboard cards stop being computed from the 100 rows the list page
    // happened to fetch.

    /** Leads created inside the window, limited to the caller's visible owners. */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.createdAt >= :from AND l.createdAt < :to
            """)
    long countCreatedBetweenForUsers(@Param("tenantId") Long tenantId,
                                     @Param("userIds") Collection<Long> userIds,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /**
     * Leads WON inside the window, measured on {@code convertedAt}.
     *
     * <p>Distinct from counting {@code leadStage = CONVERTED} today: cancelling a booking with
     * MOVE_TO_LEAD clears {@code convertedAt} and reopens the lead, so this answers "what did we
     * win in March" only for wins that still stand. That is the intended reading — a cancelled
     * booking is not a win — and it is why the card built on it is labelled by period.
     */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.convertedAt >= :from AND l.convertedAt < :to
            """)
    long countConvertedBetween(@Param("tenantId") Long tenantId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.convertedAt >= :from AND l.convertedAt < :to
            """)
    long countConvertedBetweenForUsers(@Param("tenantId") Long tenantId,
                                       @Param("userIds") Collection<Long> userIds,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /**
     * The cohort numerator: of the leads CREATED in this window, how many sit in {@code stage} now.
     *
     * <p>This is the only honest denominator-mate for {@link #countCreatedBetween}. Dividing
     * {@link #countConvertedBetween} by it would mix two populations — March's wins include leads
     * that arrived in January — and the resulting "conversion rate" can exceed 100%.
     */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.leadStage = :stage
              AND l.createdAt >= :from AND l.createdAt < :to
            """)
    long countCreatedBetweenInStage(@Param("tenantId") Long tenantId,
                                    @Param("stage") LeadStage stage,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage = :stage
              AND l.createdAt >= :from AND l.createdAt < :to
            """)
    long countCreatedBetweenInStageForUsers(@Param("tenantId") Long tenantId,
                                            @Param("userIds") Collection<Long> userIds,
                                            @Param("stage") LeadStage stage,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

    /**
     * Active leads whose follow-up date falls in {@code [from, to)} — used for DUE TODAY, as
     * {@code [today, tomorrow)} in the tenant's own zone. A half-open range rather than
     * {@code = today} so it stays correct if the card ever widens to "next 7 days".
     *
     * <p>Terminal leads are excluded: a follow-up date left on a lead that was lost or converted is
     * a stale field, not a task somebody owes the customer.
     */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.leadStage NOT IN :terminalStages
              AND l.followUpDate IS NOT NULL
              AND l.followUpDate >= :from AND l.followUpDate < :to
            """)
    long countFollowUpsInRange(@Param("tenantId") Long tenantId,
                               @Param("terminalStages") Collection<LeadStage> terminalStages,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage NOT IN :terminalStages
              AND l.followUpDate IS NOT NULL
              AND l.followUpDate >= :from AND l.followUpDate < :to
            """)
    long countFollowUpsInRangeForUsers(@Param("tenantId") Long tenantId,
                                       @Param("userIds") Collection<Long> userIds,
                                       @Param("terminalStages") Collection<LeadStage> terminalStages,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /**
     * Follow-ups that are already overdue: everything strictly before the caller's local today.
     * No lower bound rather than a sentinel start date — a floor like {@code LocalDate.MIN} is
     * outside the range Postgres accepts for a date and would fail at the driver.
     */
    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.leadStage NOT IN :terminalStages
              AND l.followUpDate IS NOT NULL
              AND l.followUpDate < :before
            """)
    long countFollowUpsBefore(@Param("tenantId") Long tenantId,
                              @Param("terminalStages") Collection<LeadStage> terminalStages,
                              @Param("before") LocalDate before);

    @Query("""
            SELECT COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage NOT IN :terminalStages
              AND l.followUpDate IS NOT NULL
              AND l.followUpDate < :before
            """)
    long countFollowUpsBeforeForUsers(@Param("tenantId") Long tenantId,
                                      @Param("userIds") Collection<Long> userIds,
                                      @Param("terminalStages") Collection<LeadStage> terminalStages,
                                      @Param("before") LocalDate before);

    /**
     * Active-pipeline money: {@code [Σ budget, how many rows carried one]}.
     *
     * <p>The count is selected alongside the sum on purpose — {@code budget} is nullable and often
     * empty on a fresh enquiry, so a bare sum reads as "the pipeline is worth ₹X" when it may be
     * describing four leads out of eighty. Returned as a single-row {@code List<Object[]>} (an
     * aggregate with no GROUP BY always yields exactly one row) rather than a bare {@code Object[]},
     * which Spring Data would be free to read as a multi-column projection.
     */
    @Query("""
            SELECT COALESCE(SUM(l.budget), 0), COUNT(l.budget)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.leadStage NOT IN :terminalStages
            """)
    List<Object[]> sumActiveBudget(@Param("tenantId") Long tenantId,
                                   @Param("terminalStages") Collection<LeadStage> terminalStages);

    @Query("""
            SELECT COALESCE(SUM(l.budget), 0), COUNT(l.budget)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage NOT IN :terminalStages
            """)
    List<Object[]> sumActiveBudgetForUsers(@Param("tenantId") Long tenantId,
                                           @Param("userIds") Collection<Long> userIds,
                                           @Param("terminalStages") Collection<LeadStage> terminalStages);

    /**
     * Lead count per type. Rows are {@code [LeadType, count (Long)]}; types with no leads are
     * ABSENT (GROUP BY), so the caller zero-fills from the enum.
     */
    @Query("""
            SELECT l.leadType, COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
            GROUP BY l.leadType
            """)
    List<Object[]> countLeadsByType(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT l.leadType, COUNT(l)
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
            GROUP BY l.leadType
            """)
    List<Object[]> countLeadsByTypeForUsers(@Param("tenantId") Long tenantId,
                                            @Param("userIds") Collection<Long> userIds);

    /**
     * publicIds of the leads sitting in one stage, newest first — the input to the quoted-value
     * roll-up, which has to price each lead's latest quotation through the shared formula rather
     * than SUM a column that does not exist.
     *
     * <p>{@code Pageable} is the cap, not pagination: the caller asks for a bounded slice and
     * reports the figure as approximate when the slice comes back full.
     */
    @Query("""
            SELECT l.publicId
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.leadStage = :stage
            ORDER BY l.createdAt DESC
            """)
    List<UUID> findPublicIdsByStage(@Param("tenantId") Long tenantId,
                                    @Param("stage") LeadStage stage,
                                    Pageable pageable);

    @Query("""
            SELECT l.publicId
            FROM Lead l
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND l.assignedUser.id IN :userIds
              AND l.leadStage = :stage
            ORDER BY l.createdAt DESC
            """)
    List<UUID> findPublicIdsByStageForUsers(@Param("tenantId") Long tenantId,
                                            @Param("userIds") Collection<Long> userIds,
                                            @Param("stage") LeadStage stage,
                                            Pageable pageable);
}
