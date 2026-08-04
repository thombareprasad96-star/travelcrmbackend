package com.crm.travelcrm.master.hotel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

    Optional<Hotel> findByIdAndTenantId(Long id, Long tenantId);

    Page<Hotel> findByTenantId(Long tenantId, Pageable pageable);

    @Query("""
            SELECT h FROM Hotel h
            WHERE h.tenantId = :tenantId
              AND h.city.destination.id = :destinationId
            """)
    Page<Hotel> findByTenantIdAndDestinationId(
            @Param("tenantId") Long tenantId,
            @Param("destinationId") Long destinationId,
            Pageable pageable);

    @Query("""
            SELECT h FROM Hotel h
            WHERE h.tenantId = :tenantId
              AND h.city.id = :cityId
            """)
    Page<Hotel> findByTenantIdAndCityId(
            @Param("tenantId") Long tenantId,
            @Param("cityId") Long cityId,
            Pageable pageable);

    /**
     * The paged list query behind {@code GET /api/hotels}: tenant scope + optional destination
     * filter + optional free-text search across the hotel and city name.
     *
     * <p>Both narrowing arms opt out via a sentinel rather than a second query method:
     * {@code destinationId = null} disables the destination filter, {@code q = ""} disables the
     * search. An empty string (not null) for {@code q} keeps Hibernate from having to infer a type
     * for an untyped null inside the LIKE, and the LEFT JOIN stops a hotel whose city row is
     * missing from silently dropping out of the list.
     */
    @Query("""
            SELECT h FROM Hotel h
            LEFT JOIN h.city c
            WHERE h.tenantId = :tenantId
              AND (:destinationId IS NULL OR c.destination.id = :destinationId)
              AND (:stars IS NULL OR h.stars = :stars)
              AND (:city = '' OR LOWER(COALESCE(c.name, '')) = LOWER(:city))
              AND ( :q = ''
                    OR LOWER(COALESCE(h.name, '')) LIKE CONCAT('%', LOWER(:q), '%')
                    OR LOWER(COALESCE(c.name,   '')) LIKE CONCAT('%', LOWER(:q), '%') )
            """)
    Page<Hotel> search(@Param("tenantId") Long tenantId,
                       @Param("destinationId") Long destinationId,
                       @Param("city") String city,
                       @Param("stars") Integer stars,
                       @Param("q") String q,
                       Pageable pageable);

    boolean existsByTenantIdAndNameAndCityId(Long tenantId, String name, Long cityId);

    boolean existsByTenantIdAndNameAndCityIdAndIdNot(Long tenantId, String name, Long cityId, Long id);

    // Referential guard: any (non-trashed, via softDeleteFilter) hotel still in this city?
    boolean existsByTenantIdAndCity_Id(Long tenantId, Long cityId);

    // Onboarding checklist: does this tenant have at least one hotel master? (ADD_MASTER signal)
    boolean existsByTenantId(Long tenantId);

    // ── Dropdown ──────────────────────────────────────────────────────────────

    @Query("SELECT h FROM Hotel h WHERE h.tenantId = :tenantId ORDER BY h.name ASC")
    List<Hotel> findAllByTenantIdForDropdown(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT h FROM Hotel h
            WHERE h.tenantId = :tenantId
              AND h.city.destination.id = :destinationId
            ORDER BY h.name ASC
            """)
    List<Hotel> findByTenantIdAndDestinationIdForDropdown(
            @Param("tenantId") Long tenantId,
            @Param("destinationId") Long destinationId);

    // ── Marketplace projection ────────────────────────────────────────────────
    // Every finder here spells out both the tenant and `deletedAt IS NULL` rather than leaning on
    // the ambient Hibernate filters. Import and sync run from paths where those filters may not be
    // enabled (a SuperAdmin thread has no TenantContext at all), and a projection lookup that
    // silently widens to every tenant is the one bug this whole boundary exists to prevent.

    /** This tenant's projection of a catalog hotel, if it has one. Unique by partial index. */
    Optional<Hotel> findByTenantIdAndPlatformHotelPublicIdAndDeletedAtIsNull(
            Long tenantId, UUID platformHotelPublicId);

    /** Which of these catalog hotels this tenant already imported — one query for a whole search page. */
    List<Hotel> findByTenantIdAndPlatformHotelPublicIdInAndDeletedAtIsNull(
            Long tenantId, Collection<UUID> platformHotelPublicIds);

    /**
     * How many tenants hold a projection of this catalog hotel.
     *
     * <p><b>Deliberately cross-tenant, and callable ONLY from the SuperAdmin catalog path</b>, where
     * there is no {@code TenantContext} and the tenant filter is therefore not enabled. Calling it
     * from a tenant request would return that tenant's own count and read as a platform figure.</p>
     */
    @Query("""
            SELECT COUNT(h) FROM Hotel h
            WHERE h.platformHotelPublicId = :platformHotelPublicId
              AND h.deletedAt IS NULL
            """)
    long countProjectionsAcrossTenants(@Param("platformHotelPublicId") UUID platformHotelPublicId);

    /** Every projection of a catalog hotel, across tenants — for unpublish fan-out. SuperAdmin only. */
    @Query("""
            SELECT h FROM Hotel h
            WHERE h.platformHotelPublicId = :platformHotelPublicId
              AND h.deletedAt IS NULL
            """)
    List<Hotel> findProjectionsAcrossTenants(@Param("platformHotelPublicId") UUID platformHotelPublicId);

    /**
     * The reconciliation sweep's work queue: projections the catalog has moved past, across tenants.
     *
     * <p>{@code STALE} only. {@code LOCATION_MAPPING_REQUIRED} and {@code SOURCE_INACTIVE} are
     * unresolved problems that re-copying cannot fix — one needs a city creating, the other needs the
     * hotel republishing — and sweeping them every tick would bury the drainable backlog in rows that
     * can never drain.</p>
     *
     * <p>Ordered by tenant so the caller can enter {@code TenantScope} once per tenant instead of once
     * per row. Deliberately cross-tenant, and therefore callable only from a platform thread that
     * re-scopes before it writes.</p>
     */
    @Query("""
            SELECT h FROM Hotel h
            WHERE h.deletedAt IS NULL
              AND h.origin = com.crm.travelcrm.master.hotel.HotelOrigin.PLATFORM_SYNC
              AND h.syncStatus = com.crm.travelcrm.master.hotel.HotelSyncStatus.STALE
              AND h.platformHotelPublicId IS NOT NULL
            ORDER BY h.tenantId ASC, h.id ASC
            """)
    List<Hotel> findStaleProjectionsAcrossTenants(Pageable pageable);
}