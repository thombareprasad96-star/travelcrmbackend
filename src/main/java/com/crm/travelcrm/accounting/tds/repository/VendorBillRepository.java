package com.crm.travelcrm.accounting.tds.repository;

import com.crm.travelcrm.accounting.tds.entity.VendorBill;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VendorBillRepository extends JpaRepository<VendorBill, Long> {

    Optional<VendorBill> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Page<VendorBill> findByTenantIdAndDeletedAtIsNullOrderByIdDesc(Long tenantId, Pageable pageable);

    List<VendorBill> findByTenantIdAndBillDateBetweenAndDeletedAtIsNull(
            Long tenantId, LocalDate from, LocalDate to);

    /** Total still owed to vendors — the "Outstanding Payable" KPI. */
    @Query("SELECT COALESCE(SUM(b.netPayable - b.amountPaid), 0) FROM VendorBill b "
            + "WHERE b.tenantId = :tenantId AND b.deletedAt IS NULL AND b.status <> 'CANCELLED'")
    BigDecimal sumOutstandingPayable(@Param("tenantId") Long tenantId);
}