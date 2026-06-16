package com.crm.travelcrm.master.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {

    @Query("SELECT v FROM VehicleEntity v WHERE v.tenantId IS NULL OR v.tenantId = :tenantId")
    Page<VehicleEntity> findAllVisibleTo(@Param("tenantId") Long tenantId, Pageable pageable);

    @Query("SELECT v FROM VehicleEntity v WHERE (v.tenantId IS NULL OR v.tenantId = :tenantId) AND LOWER(v.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<VehicleEntity> searchByName(@Param("tenantId") Long tenantId, @Param("q") String q);

    @Query("SELECT v FROM VehicleEntity v WHERE (v.tenantId IS NULL OR v.tenantId = :tenantId) AND LOWER(v.type) = LOWER(:type)")
    List<VehicleEntity> findByTypeVisible(@Param("tenantId") Long tenantId, @Param("type") String type);

    boolean existsByNameAndTenantId(String name, Long tenantId);
}