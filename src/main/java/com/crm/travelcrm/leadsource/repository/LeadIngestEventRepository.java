package com.crm.travelcrm.leadsource.repository;

import com.crm.travelcrm.leadsource.entity.LeadIngestEvent;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadIngestEventRepository extends JpaRepository<LeadIngestEvent, Long> {

    /**
     * Delivery history for one connection — the tenant-facing "what arrived" list.
     *
     * <p>Returns a projection, not the entity, so {@code raw_payload} never loads: see
     * {@link LeadIngestEventView}. Backed by {@code idx_lead_ingest_integration}
     * {@code (tenant_id, integration_id, created_at DESC)}.
     */
    Page<LeadIngestEventView> findByTenantIdAndIntegrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Long integrationId, Pageable pageable);

    Page<LeadIngestEvent> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Pageable pageable);

    Optional<LeadIngestEvent> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * Tenant-scoped by-id lookup, for the ingest pipeline stamping a delivery's terminal status.
     *
     * <p>Not {@code findById}: that is banned on a {@code BaseTenantEntity} repository by the
     * tenant-isolation ArchUnit rule, and correctly so — it bypasses the tenant filter entirely.
     * No {@code deletedAtIsNull} here on purpose: an ingest event is an audit record and its status
     * must be settable regardless.
     */
    Optional<LeadIngestEvent> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Content-level idempotency probe.
     *
     * <p>Scoped by tenant AND channel — deliberately not a platform-wide unique. An external event id
     * is provider-controlled and namespaced by an account we do not administer, so a platform-wide key
     * would let one connection permanently poison it for every other tenant: silent cross-tenant lead
     * LOSS.
     */
    Optional<LeadIngestEvent> findFirstByTenantIdAndChannelAndExternalEventIdAndDeletedAtIsNull(
            Long tenantId, String channel, String externalEventId);

    /** Transport-window idempotency probe (a provider retrying the same delivery). */
    Optional<LeadIngestEvent> findFirstByTenantIdAndChannelAndDedupKeyAndDeletedAtIsNull(
            Long tenantId, String channel, String dedupKey);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, LeadIngestStatus status);
}
