package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.repository.LeadSourceIntegrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Bumps a connection's delivery counters.
 *
 * <p><b>Why this is its own bean rather than a repository call from the gateway.</b> The counter
 * updates are {@code @Modifying} JPQL, which requires an active transaction — and the gateway is
 * deliberately NON-transactional, because {@code TenantFilterAspect} latches the tenant filter
 * {@code @Before} a {@code @Transactional} method and the tenant must be set before that happens.
 * Calling the repository straight from the gateway throws
 * {@code InvalidDataAccessApiUsageException: No EntityManager with actual transaction available}, and
 * — since the gateway catches everything to keep the provider's response a 200 — the symptom is not a
 * crash but a delivery recorded FAILED <em>after the lead was already created</em>. So the row exists,
 * the event says it does not, and nothing looks broken from the outside.
 *
 * <p>{@code REQUIRES_NEW} for the same reason as {@link LeadIngestDeliveryLogger}: a counter is
 * bookkeeping and must never take a real lead down with it, in either direction.
 *
 * <p>Called CROSS-BEAN from the gateway, inside a {@code TenantScope}. Self-invocation would defeat
 * both the proxy and the aspect.
 */
@Service
@RequiredArgsConstructor
public class LeadSourceCounterService {

    private final LeadSourceIntegrationRepository repository;

    /** A delivery produced a lead: bump the count and both timestamps. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLeadReceived(Long integrationId) {
        repository.recordLeadReceived(integrationId, LocalDateTime.now());
    }

    /**
     * A delivery arrived but produced no lead (an ignored ping, a duplicate). Touch-only.
     *
     * <p>Worth recording separately: "this connection has received nothing, ever" and "this connection
     * receives pings but no leads" are different problems with different fixes, and a tenant staring
     * at a silent integration needs to know which one they have.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokenUsed(Long integrationId) {
        repository.recordTokenUsed(integrationId, LocalDateTime.now());
    }
}
