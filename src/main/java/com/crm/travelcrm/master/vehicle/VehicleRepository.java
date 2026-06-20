package com.crm.travelcrm.master.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleRepository extends JpaRepository<VehicleEntity, Long> {

    @Query("SELECT v FROM VehicleEntity v WHERE v.tenantId IS NULL OR v.tenantId = :tenantId")
    Page<VehicleEntity> findAllVisibleTo(@Param("tenantId") Long tenantId, Pageable pageable);

    // Single-item read: a vehicle is visible if it is global (null tenant) or owned by this tenant.
    @Query("SELECT v FROM VehicleEntity v WHERE v.publicId = :publicId AND (v.tenantId IS NULL OR v.tenantId = :tenantId)")
    Optional<VehicleEntity> findVisibleByPublicId(@Param("publicId") UUID publicId, @Param("tenantId") Long tenantId);

    // Tenant-scoped lookup (used for edit/delete — global vehicles are NOT editable by tenants).
    Optional<VehicleEntity> findByPublicIdAndTenantId(UUID publicId, Long tenantId);

    // Platform-admin (SuperAdmin) lookup — no tenant scoping.
    Optional<VehicleEntity> findByPublicId(UUID publicId);

    @Query("SELECT v FROM VehicleEntity v WHERE (v.tenantId IS NULL OR v.tenantId = :tenantId) AND LOWER(v.name) LIKE LOWER(CONCAT('%', :q, '%'))")
    List<VehicleEntity> searchByName(@Param("tenantId") Long tenantId, @Param("q") String q);

    @Query("SELECT v FROM VehicleEntity v WHERE (v.tenantId IS NULL OR v.tenantId = :tenantId) AND LOWER(v.type) = LOWER(:type)")
    List<VehicleEntity> findByTypeVisible(@Param("tenantId") Long tenantId, @Param("type") String type);

    boolean existsByNameAndTenantId(String name, Long tenantId);

    @Query("SELECT v FROM VehicleEntity v WHERE (v.tenantId IS NULL OR v.tenantId = :tenantId) ORDER BY v.name ASC")
    List<VehicleEntity> findAllVisibleToOrderByNameAsc(@Param("tenantId") Long tenantId);
}