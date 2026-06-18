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

    boolean existsByTenantIdAndNameAndCityId(Long tenantId, String name, Long cityId);

    boolean existsByTenantIdAndNameAndCityIdAndIdNot(Long tenantId, String name, Long cityId, Long id);

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