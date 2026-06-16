package com.crm.travelcrm.vendor.repository;

import com.crm.travelcrm.vendor.entity.VendorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<VendorEntity, Long>, JpaSpecificationExecutor<VendorEntity> {

    Optional<VendorEntity> findTopByOrderByIdDesc();

    Optional<VendorEntity> findByVendorCodeAndTenantId(String vendorCode, Long tenantId);

    List<VendorEntity> findByTenantIdAndVendorType(Long tenantId, String vendorType);

    List<VendorEntity> findByTenantIdAndStatus(Long tenantId, String status);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, String status);

    long countByTenantIdAndVendorType(Long tenantId, String vendorType);

    @Query("SELECT COALESCE(SUM(v.totalBusiness), 0) FROM VendorEntity v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL")
    BigDecimal sumTotalBusinessByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(v.totalPaid), 0) FROM VendorEntity v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL")
    BigDecimal sumTotalPaidByTenantId(@Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(AVG(v.rating), 0.0) FROM VendorEntity v WHERE v.tenantId = :tenantId AND v.rating IS NOT NULL AND v.deletedAt IS NULL")
    Double avgRatingByTenantId(@Param("tenantId") Long tenantId);

    List<VendorEntity> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);

    @Query("""
            SELECT v FROM VendorEntity v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL
            AND (LOWER(v.vendorName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.vendorCode) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.city)       LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.email)      LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.phone)      LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    List<VendorEntity> search(@Param("tenantId") Long tenantId, @Param("q") String q);
}