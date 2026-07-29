package com.crm.travelcrm.master.hotel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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
}