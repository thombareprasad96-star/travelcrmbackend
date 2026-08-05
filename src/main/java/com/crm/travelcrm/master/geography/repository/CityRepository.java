package com.crm.travelcrm.master.geography.repository;

import com.crm.travelcrm.master.geography.entity.City;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /**
     * Name-only fallback, used when resolving a {@code LeadItinerary} leg to a real {@link City}:
     * the lead stores free-typed {@code destination} + {@code city} strings, and the destination is
     * often blank or spelled differently, so the destination-qualified finder above misses.
     *
     * <p>{@code findFirst…OrderByIdAsc} rather than a plain {@code Optional} finder: a city name is
     * only unique per {@code (tenant, country)}, so "Springfield" in two countries would otherwise
     * throw {@code IncorrectResultSizeDataAccessException}. Oldest row wins, deterministically.
     */
    Optional<City> findFirstByTenantIdAndNameIgnoreCaseOrderByIdAsc(Long tenantId, String name);

    /**
     * Country-qualified name match — the safe lookup for marketplace hotel sync.
     *
     * <p>The catalog stores geography as an ISO country code plus a city name, and the sync resolves
     * the country first so this can narrow by it. Country-qualified because city names repeat across
     * countries (Hyderabad, Birmingham, Santiago): the name-only fallback above is acceptable for a
     * lead the user typed by hand, but attaching a platform hotel to the wrong country's city
     * produces a record that reads as correct on every screen downstream.</p>
     */
    Optional<City> findFirstByTenantIdAndCountryIdAndNameIgnoreCaseOrderByIdAsc(
            Long tenantId, Long countryId, String name);

    List<City> findByTenantIdAndDestination_NameIgnoreCase(Long tenantId, String destinationName);

    /**
     * Batch geography lookup — "which destination and country do these city ids belong to?" — as a
     * constructor projection, in one query.
     *
     * <p>Used by package-template matching to grade near-misses (a different town in the same
     * destination is worth partial credit). Hydrating {@code City} entities and walking
     * {@code getDestination().getId()} per row would either initialise a proxy per city or add a
     * select per city; this is one select for the whole ranking pass regardless of how many
     * templates are in play.
     *
     * <p>{@code LEFT JOIN} on destination because that parent is optional; the country join is inner
     * because {@code country_id} is NOT NULL.
     */
    @Query("""
            SELECT new com.crm.travelcrm.master.geography.repository.CityGeoRef(
                       c.id, c.name, d.id, co.id)
            FROM City c
            LEFT JOIN c.destination d
            JOIN c.country co
            WHERE c.tenantId = :tenantId AND c.id IN :ids
            """)
    List<CityGeoRef> findGeoRefsByTenantIdAndIdIn(@Param("tenantId") Long tenantId,
                                                  @Param("ids") Collection<Long> ids);

    /** Dropdown: all cities under a specific destination for this tenant, ordered by name. */
    List<City> findByTenantIdAndDestinationIdOrderByNameAsc(Long tenantId, Long destinationId);

    // ── Referential guard (non-trashed children, via softDeleteFilter) ────────
    boolean existsByTenantIdAndCountryId(Long tenantId, Long countryId);
    boolean existsByTenantIdAndDestinationId(Long tenantId, Long destinationId);

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