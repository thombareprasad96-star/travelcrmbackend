package com.crm.travelcrm.master.sightseeing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SightseeingRepository extends JpaRepository<Sightseeing, Long> {

    Optional<Sightseeing> findByIdAndTenantId(Long id, Long tenantId);

    Page<Sightseeing> findByTenantId(Long tenantId, Pageable pageable);

    @Query("""
            SELECT s FROM Sightseeing s
            WHERE s.tenantId = :tenantId
              AND s.city.id = :cityId
            """)
    Page<Sightseeing> findByTenantIdAndCityId(
            @Param("tenantId") Long tenantId,
            @Param("cityId") Long cityId,
            Pageable pageable);

    @Query("""
            SELECT s FROM Sightseeing s
            WHERE s.tenantId = :tenantId
              AND s.city.destination.id = :destinationId
            """)
    Page<Sightseeing> findByTenantIdAndDestinationId(
            @Param("tenantId") Long tenantId,
            @Param("destinationId") Long destinationId,
            Pageable pageable);

    @Query("""
            SELECT s FROM Sightseeing s
            WHERE s.tenantId = :tenantId
              AND (:destination IS NULL OR LOWER(s.city.destination.name) LIKE LOWER(CONCAT('%', :destination, '%')))
              AND (:city IS NULL OR LOWER(s.city.name) LIKE LOWER(CONCAT('%', :city, '%')))
            """)
    Page<Sightseeing> filterByNames(
            @Param("tenantId") Long tenantId,
            @Param("destination") String destination,
            @Param("city") String city,
            Pageable pageable);

    @Query("""
            SELECT s FROM Sightseeing s
            WHERE s.tenantId = :tenantId
              AND LOWER(s.title) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    List<Sightseeing> searchByTitle(@Param("tenantId") Long tenantId, @Param("q") String q);

    // ── Dropdown ──────────────────────────────────────────────────────────────

    @Query("SELECT s FROM Sightseeing s WHERE s.tenantId = :tenantId ORDER BY s.title ASC")
    List<Sightseeing> findAllByTenantIdForDropdown(@Param("tenantId") Long tenantId);

    @Query("""
            SELECT s FROM Sightseeing s
            WHERE s.tenantId = :tenantId
              AND s.city.destination.id = :destinationId
            ORDER BY s.title ASC
            """)
    List<Sightseeing> findByTenantIdAndDestinationIdForDropdown(
            @Param("tenantId") Long tenantId,
            @Param("destinationId") Long destinationId);

    // Referential guard: any (non-trashed, via softDeleteFilter) sightseeing still in this city?
    boolean existsByTenantIdAndCity_Id(Long tenantId, Long cityId);
}