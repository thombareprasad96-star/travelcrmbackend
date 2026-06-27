package com.crm.travelcrm.master.airline;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AirlineRepository extends JpaRepository<Airline, Long> {

    Optional<Airline> findByIdAndTenantId(Long id, Long tenantId);

    Page<Airline> findByTenantId(Long tenantId, Pageable pageable);

    List<Airline> findByTenantIdOrderByNameAsc(Long tenantId);

    @Query("""
            SELECT a FROM Airline a
            WHERE a.tenantId = :tenantId
              AND a.city.id = :cityId
            """)
    Page<Airline> findByTenantIdAndCityId(
            @Param("tenantId") Long tenantId,
            @Param("cityId") Long cityId,
            Pageable pageable);

    // Referential guard: any (non-trashed, via softDeleteFilter) airline still in this city?
    boolean existsByTenantIdAndCity_Id(Long tenantId, Long cityId);
}