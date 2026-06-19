package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.Destination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped data access for {@link Destination}. */
@Repository
public interface DestinationRepository extends JpaRepository<Destination, Long> {

    // ── Tenant-scoped lookups ─────────────────────────────────────────────────

    Optional<Destination> findByIdAndTenantId(Long id, Long tenantId);

    Page<Destination> findByTenantIdAndCountryId(
            Long tenantId, Long countryId, Pageable pageable);

    Page<Destination> findAllByTenantId(Long tenantId, Pageable pageable);

    // ── Visibility query: global OR tenant-owned ──────────────────────────────
    // FIX: added @Query — Spring Data cannot derive this from the method name

    @Query("SELECT d FROM Destination d WHERE d.global = true OR d.tenantId = :tenantId")
    Page<Destination> findAllVisibleTo(@Param("tenantId") Long tenantId, Pageable pageable);

    /**
     * Dropdown: destinations under a given country visible to this tenant.
     * Includes platform-managed global destinations (d.global = true) AND the
     * tenant's own destinations. Filters to status = 'Active' when set;
     * a null/blank status is treated as active so seed data is included.
     * Results are alphabetically ordered for UI rendering.
     */
    @Query("""
            SELECT d FROM Destination d
            WHERE (d.global = true OR d.tenantId = :tenantId)
              AND d.country.id = :countryId
              AND (d.status IS NULL OR LOWER(d.status) = 'active')
            ORDER BY d.name ASC
            """)
    List<Destination> findActiveByCountryIdVisibleTo(
            @Param("tenantId") Long tenantId,
            @Param("countryId") Long countryId);

    /**
     * Dropdown: ALL active destinations visible to this tenant, across every
     * country. Used by flows where a tenant works with a fixed handful of
     * destinations and there is no country pre-filter (e.g. the lead itinerary
     * builder). Includes platform-managed global destinations and the tenant's
     * own; a null/blank status is treated as active. Alphabetically ordered.
     */
    @Query("""
            SELECT d FROM Destination d
            WHERE (d.global = true OR d.tenantId = :tenantId)
              AND (d.status IS NULL OR LOWER(d.status) = 'active')
            ORDER BY d.name ASC
            """)
    List<Destination> findAllActiveVisibleTo(@Param("tenantId") Long tenantId);

    /** Used to validate that a destination is visible to the requesting tenant. */
    @Query("""
            SELECT d FROM Destination d
            WHERE d.id = :id
              AND (d.global = true OR d.tenantId = :tenantId)
            """)
    Optional<Destination> findByIdVisibleTo(
            @Param("id") Long id,
            @Param("tenantId") Long tenantId);

    // ── Duplicate guard (name unique per tenant) ──────────────────────────────

    boolean existsByTenantIdAndName(Long tenantId, String name);

    boolean existsByTenantIdAndNameAndIdNot(Long tenantId, String name, Long id);
}