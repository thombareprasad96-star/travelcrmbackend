package com.crm.travelcrm.master.addon;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddonRepository extends JpaRepository<Addon, Long> {

    Optional<Addon> findByIdAndTenantId(Long id, Long tenantId);

    Page<Addon> findByTenantId(Long tenantId, Pageable pageable);

    @Query("""
            SELECT a FROM Addon a
            WHERE a.tenantId = :tenantId
              AND a.city.id = :cityId
            """)
    List<Addon> findByTenantIdAndActiveTrueOrderByNameAsc(Long tenantId);

    Page<Addon> findByTenantIdAndCityId(
            @Param("tenantId") Long tenantId,
            @Param("cityId") Long cityId,
            Pageable pageable);

    // Referential guard: any (non-trashed, via softDeleteFilter) add-on still in this city?
    boolean existsByTenantIdAndCity_Id(Long tenantId, Long cityId);
}