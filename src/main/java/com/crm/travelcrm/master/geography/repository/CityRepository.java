package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.City;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped data access for {@link City}. */
@Repository
public interface CityRepository extends JpaRepository<City, Long> {

    Optional<City> findByIdAndTenantId(Long id, Long tenantId);

    // ── By country (the city's required parent) ───────────────────────────────
    Page<City> findByTenantIdAndCountryId(Long tenantId, Long countryId, Pageable pageable);

    /** Dropdown: all cities under a country for this tenant, ordered by name. */
    List<City> findByTenantIdAndCountryIdOrderByNameAsc(Long tenantId, Long countryId);

    // ── By destination (optional parent) ──────────────────────────────────────
    Page<City> findByTenantIdAndDestinationId(Long tenantId, Long destinationId, Pageable pageable);

    /** All cities under a given country reached via the destination link. */
    Page<City> findByTenantIdAndDestination_CountryId(Long tenantId, Long countryId, Pageable pageable);

    Page<City> findAllByTenantId(Long tenantId, Pageable pageable);

    Optional<City> findByTenantIdAndDestinationIdAndNameIgnoreCase(
            Long tenantId, Long destinationId, String name);

    Optional<City> findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(
            Long tenantId, String destinationName, String cityName);

    List<City> findByTenantIdAndDestination_NameIgnoreCase(Long tenantId, String destinationName);

    /** Dropdown: all cities under a specific destination for this tenant, ordered by name. */
    List<City> findByTenantIdAndDestinationIdOrderByNameAsc(Long tenantId, Long destinationId);

    // ── Duplicate guard (name unique per tenant + country) ────────────────────
    boolean existsByTenantIdAndCountryIdAndName(Long tenantId, Long countryId, String name);
    boolean existsByTenantIdAndCountryIdAndNameAndIdNot(
            Long tenantId, Long countryId, String name, Long id);

    // ── Detach cities from a destination (used when a destination is deleted) ──
    @Modifying(clearAutomatically = true)
    @Query("UPDATE City c SET c.destination = null "
            + "WHERE c.destination.id = :destinationId AND c.tenantId = :tenantId")
    int detachFromDestination(@Param("tenantId") Long tenantId,
                              @Param("destinationId") Long destinationId);
}