package com.crm.travelcrm.booking.cancellation.repository;

import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CancellationPolicyRepository extends JpaRepository<CancellationPolicy, Long> {

    /** Load one specific version by its publicId — used to load the version a booking pinned (even if since superseded). */
    Optional<CancellationPolicy> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * Point-in-time COMPANY default: the company version that was <b>in force</b> as of {@code asOf}
     * — the greatest {@code effectiveFrom} on or before that date — regardless of whether it is the
     * currently-active version. Deliberately does NOT filter on {@code active}: superseding publishes
     * a new row and deactivates the old one, but the old version still governs bookings from its era,
     * so point-in-time resolution must see it. Soft-deleting a version (deletedAt) is the only way to
     * remove it from resolution. Call with {@code PageRequest.of(0,1)} and take the head.
     */
    @Query("""
           select p from CancellationPolicy p
           where p.tenantId = :tenantId
             and p.level = com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel.COMPANY
             and p.ownerPublicId is null
             and p.deletedAt is null
             and p.effectiveFrom <= :asOf
           order by p.effectiveFrom desc, p.version desc
           """)
    List<CancellationPolicy> findCompanyVersionAsOf(@Param("tenantId") Long tenantId,
                                                    @Param("asOf") LocalDate asOf,
                                                    Pageable pageable);

    /** The currently-active PACKAGE policy for a given owning package publicId. */
    Optional<CancellationPolicy> findFirstByTenantIdAndLevelAndOwnerPublicIdAndActiveTrueAndDeletedAtIsNull(
            Long tenantId, CancellationPolicyLevel level, UUID ownerPublicId);

    /** Whether the tenant already has any (non-deleted) company default — seeding idempotency guard. */
    boolean existsByTenantIdAndLevelAndOwnerPublicIdIsNullAndDeletedAtIsNull(
            Long tenantId, CancellationPolicyLevel level);

    /** All currently-active policies for the tenant (CRUD list view). */
    List<CancellationPolicy> findByTenantIdAndActiveTrueAndDeletedAtIsNullOrderByLevelAscCreatedAtDesc(Long tenantId);

    /** Full version history for one logical policy (a level + owner), newest version first. */
    List<CancellationPolicy> findByTenantIdAndLevelAndOwnerPublicIdAndDeletedAtIsNullOrderByVersionDesc(
            Long tenantId, CancellationPolicyLevel level, UUID ownerPublicId);

    /** Company-default version history (owner is null). */
    List<CancellationPolicy> findByTenantIdAndLevelAndOwnerPublicIdIsNullAndDeletedAtIsNullOrderByVersionDesc(
            Long tenantId, CancellationPolicyLevel level);
}