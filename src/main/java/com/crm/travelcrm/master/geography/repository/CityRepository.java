package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.City;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped data access for {@link City}. */
@Repository
public interface CityRepository extends JpaRepository<City, Long> {

    Optional<City> findByIdAndTenantId(Long id, Long tenantId);

    Page<City> findByTenantIdAndDestinationId(Long tenantId, Long destinationId, Pageable pageable);

    /** All cities under a given country (City → Destination → Country). */
    Page<City> findByTenantIdAndDestination_CountryId(Long tenantId, Long countryId, Pageable pageable);

    Page<City> findAllByTenantId(Long tenantId, Pageable pageable);

    Optional<City> findByTenantIdAndDestinationIdAndNameIgnoreCase(
            Long tenantId, Long destinationId, String name);

    Optional<City> findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(
            Long tenantId, String destinationName, String cityName);

    List<City> findByTenantIdAndDestination_NameIgnoreCase(Long tenantId, String destinationName);

    /** Dropdown: all cities under a specific destination for this tenant, ordered by name. */
    List<City> findByTenantIdAndDestinationIdOrderByNameAsc(Long tenantId, Long destinationId);

    // ── Duplicate guard (name unique per tenant + destination) ─────────────────
    boolean existsByTenantIdAndNameAndDestinationId(Long tenantId, String name, Long destinationId);
    boolean existsByTenantIdAndNameAndDestinationIdAndIdNot(
            Long tenantId, String name, Long destinationId, Long id);
}