package com.crm.travelcrm.master.cruise;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CruiseRepository extends JpaRepository<Cruise, Long> {

    Optional<Cruise> findByIdAndTenantId(Long id, Long tenantId);

    Page<Cruise> findByTenantId(Long tenantId, Pageable pageable);

    List<Cruise> findByTenantIdOrderByNameAsc(Long tenantId);

    @Query("""
            SELECT c FROM Cruise c
            WHERE c.tenantId = :tenantId
              AND c.city.id = :cityId
            """)
    Page<Cruise> findByTenantIdAndCityId(
            @Param("tenantId") Long tenantId,
            @Param("cityId") Long cityId,
            Pageable pageable);
}