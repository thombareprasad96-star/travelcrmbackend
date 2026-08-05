package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommContactIdentity;
import com.crm.travelcrm.communication.enums.ContactIdentityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Canonical contact addresses. Lookups are always on the CANONICAL value, never the raw input. */
public interface CommContactIdentityRepository extends JpaRepository<CommContactIdentity, Long> {

    Optional<CommContactIdentity> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * By internal id, tenant-scoped.
     *
     * <p>Exists because {@code findById} is BANNED on tenant entities by
     * {@code TenantIsolationArchTest} — Hibernate's {@code tenantFilter} never applies to
     * {@code EntityManager.find}, so a bare primary-key lookup is a silent cross-tenant read.
     */
    Optional<CommContactIdentity> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /** Batch variant for a page of conversations — one query instead of one per row. */
    List<CommContactIdentity> findByTenantIdAndIdInAndDeletedAtIsNull(Long tenantId, Collection<Long> ids);

    /**
     * The natural-key lookup, backed by {@code uq_cci_identity}.
     *
     * <p>{@code identityValue} MUST already be canonical — E.164 through {@code PhoneCanonicalizer}
     * (the same authority {@code CustomerMatcher} uses, so a thread and a customer match can never
     * disagree) or lower-cased for email. Passing raw input here silently creates a second identity
     * for the same person and splits their thread.
     */
    Optional<CommContactIdentity> findByTenantIdAndIdentityTypeAndIdentityValueAndDeletedAtIsNull(
            Long tenantId, ContactIdentityType identityType, String identityValue);

    List<CommContactIdentity> findByTenantIdAndCustomerIdAndDeletedAtIsNull(Long tenantId, Long customerId);

    List<CommContactIdentity> findByTenantIdAndLeadIdAndDeletedAtIsNull(Long tenantId, Long leadId);

    /** Contact lookup for the search bar: matches the address or the cached display name. */
    @Query("""
            SELECT ci FROM CommContactIdentity ci
            WHERE ci.tenantId = :tenantId AND ci.deletedAt IS NULL
              AND (LOWER(ci.identityValue) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(ci.displayName) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY ci.displayName ASC
            """)
    List<CommContactIdentity> search(@Param("tenantId") Long tenantId, @Param("q") String q);
}
