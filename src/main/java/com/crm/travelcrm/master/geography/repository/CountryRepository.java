package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.Country;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Tenant-scoped data access for {@link Country}. */
@Repository
public interface CountryRepository extends JpaRepository<Country, Long> {

    Optional<Country> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Country> findByTenantIdAndCode(Long tenantId, String code);

    Optional<Country> findByTenantIdAndName(Long tenantId, String name);

    Page<Country> findAllByTenantId(Long tenantId, Pageable pageable);

    // ── Duplicate guards (unique per tenant) ───────────────────────────────────
    boolean existsByTenantIdAndName(Long tenantId, String name);
    boolean existsByTenantIdAndCode(Long tenantId, String code);
    boolean existsByTenantIdAndNameAndIdNot(Long tenantId, String name, Long id);
    boolean existsByTenantIdAndCodeAndIdNot(Long tenantId, String code, Long id);
}