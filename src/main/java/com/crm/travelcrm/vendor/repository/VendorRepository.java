package com.crm.travelcrm.vendor.repository;

import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, Long>, JpaSpecificationExecutor<Vendor> {

    // ── FIND OPERATIONS ──────────────────────────────────────────────────────

    Optional<Vendor> findTopByOrderByIdDesc();

    // Tenant-scoped single-item lookups — never use bare findById(Long) (bypasses tenant filter).
    Optional<Vendor> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Vendor> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    Optional<Vendor> findByPublicIdAndTenantId(java.util.UUID publicId, Long tenantId);

    Optional<Vendor> findByVendorCodeAndTenantId(String vendorCode, Long tenantId);

    Optional<Vendor> findByVendorCodeAndTenantIdAndDeletedAtIsNull(String vendorCode, Long tenantId);

    List<Vendor> findByTenantIdAndVendorType(Long tenantId, String vendorType);

    List<Vendor> findByTenantIdAndVendorTypeAndDeletedAtIsNull(Long tenantId, String vendorType);

    List<Vendor> findByTenantIdAndStatus(Long tenantId, VendorStatus status);

    @EntityGraph(attributePaths = {"services"})
    List<Vendor> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);

    // ── COUNT OPERATIONS ─────────────────────────────────────────────────────

    long countByTenantId(Long tenantId);

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndStatus(Long tenantId, VendorStatus status);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, VendorStatus status);

    long countByTenantIdAndVendorType(Long tenantId, String vendorType);

    long countByTenantIdAndVendorTypeAndDeletedAtIsNull(Long tenantId, String vendorType);

    // ── SUM OPERATIONS ───────────────────────────────────────────────────────

    @Query("SELECT COALESCE(SUM(v.totalBusiness), 0) FROM Vendor v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL")
    BigDecimal sumTotalBusinessByTenantIdAndDeletedAtIsNull(@Param("tenantId") Long tenantId);

    @Query("SELECT COALESCE(SUM(v.totalPaid), 0) FROM Vendor v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL")
    BigDecimal sumTotalPaidByTenantIdAndDeletedAtIsNull(@Param("tenantId") Long tenantId);

    // ── AVG OPERATIONS ───────────────────────────────────────────────────────

    @Query("SELECT COALESCE(AVG(v.rating), 0.0) FROM Vendor v WHERE v.tenantId = :tenantId AND v.rating IS NOT NULL AND v.deletedAt IS NULL")
    Double avgRatingByTenantIdAndDeletedAtIsNull(@Param("tenantId") Long tenantId);

    // ── SEARCH OPERATIONS ────────────────────────────────────────────────────

    @Query("""
            SELECT v FROM Vendor v WHERE v.tenantId = :tenantId AND v.deletedAt IS NULL
            AND (LOWER(v.vendorName) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.vendorCode) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.city)       LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.email)      LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(v.phone)      LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    List<Vendor> search(@Param("tenantId") Long tenantId, @Param("q") String q);
}