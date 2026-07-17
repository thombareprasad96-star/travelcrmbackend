package com.crm.travelcrm.leadsource.repository;

import com.crm.travelcrm.leadsource.entity.LeadSourceIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadSourceIntegrationRepository extends JpaRepository<LeadSourceIntegration, Long> {

    /**
     * THE tenant resolver. Runs on the public ingest path with NO tenant context — by definition, since
     * finding the tenant is what it is for.
     *
     * <p>This is a derived finder rather than {@code findById}, which the tenant-isolation ArchUnit
     * rule bans; the rule permits derived finders. That is correct here and not a loophole: this query
     * is keyed on 256 bits of CSPRNG output, so "the wrong tenant" is not reachable by guessing.
     */
    Optional<LeadSourceIntegration> findByIngestTokenHashAndDeletedAtIsNull(String ingestTokenHash);

    /** The overlapping-rotation fallback. The caller MUST still check {@code tokenPreviousRevokeAt}. */
    Optional<LeadSourceIntegration> findByIngestTokenHashPreviousAndDeletedAtIsNull(String hash);

    /** PROVIDER_ACCOUNT resolution — only after the platform HMAC has proven the body's authenticity. */
    Optional<LeadSourceIntegration> findByChannelAndExternalAccountIdAndDeletedAtIsNull(
            String channel, String externalAccountId);

    // ── Tenant-scoped management reads ──

    List<LeadSourceIntegration> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    List<LeadSourceIntegration> findByTenantIdAndChannelAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, String channel);

    Optional<LeadSourceIntegration> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * Bump the delivery counters ATOMICALLY.
     *
     * <p>Never read-modify-write, and deliberately never {@code @Version}-guarded: an optimistic-lock
     * conflict on a STATS COUNTER must never be able to reject a real lead. Two concurrent deliveries
     * to one busy connection is the normal case, not the edge case.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE LeadSourceIntegration i
               SET i.leadCount = i.leadCount + 1,
                   i.lastLeadReceivedAt = :now,
                   i.tokenLastUsedAt = :now
             WHERE i.id = :id
            """)
    void recordLeadReceived(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Touch-only, for a delivery that resolved but produced no lead (an ignored ping, a duplicate). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE LeadSourceIntegration i SET i.tokenLastUsedAt = :now WHERE i.id = :id")
    void recordTokenUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
