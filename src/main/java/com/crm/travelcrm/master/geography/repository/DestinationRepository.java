package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.Destination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    // ── Duplicate guard (name unique per tenant) ──────────────────────────────

    boolean existsByTenantIdAndName(Long tenantId, String name);

    boolean existsByTenantIdAndNameAndIdNot(Long tenantId, String name, Long id);
}