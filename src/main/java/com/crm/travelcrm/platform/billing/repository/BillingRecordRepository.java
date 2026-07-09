package com.crm.travelcrm.platform.billing.repository;

import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Platform-level billing (no tenant scoping — scoped explicitly by {@code tenantId}). */
@Repository
public interface BillingRecordRepository extends JpaRepository<BillingRecord, Long> {

    Optional<BillingRecord> findByPublicId(UUID publicId);

    Optional<BillingRecord> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** Newest invoice first. */
    List<BillingRecord> findByTenantIdAndDeletedAtIsNullOrderByIssueDateDescIdDesc(Long tenantId);

    /** Grouped count of records in a status for a set of tenants — feeds the tenant-list "unpaid" badge. */
    @Query("SELECT b.tenantId, COUNT(b) FROM BillingRecord b "
            + "WHERE b.deletedAt IS NULL AND b.status = :status AND b.tenantId IN :tenantIds "
            + "GROUP BY b.tenantId")
    List<Object[]> countByStatusGroupedByTenant(@Param("status") BillingStatus status,
                                                @Param("tenantIds") List<Long> tenantIds);

    /** Σ invoice amount in a status — total outstanding (UNPAID) / collected (PAID). */
    @Query("SELECT COALESCE(SUM(b.amount), 0) FROM BillingRecord b "
            + "WHERE b.deletedAt IS NULL AND b.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") BillingStatus status);

    /** Invoices in a status issued on/after a date — feeds the revenue-by-month series. */
    List<BillingRecord> findByStatusAndDeletedAtIsNullAndIssueDateGreaterThanEqual(
            BillingStatus status, LocalDate issueDate);
}