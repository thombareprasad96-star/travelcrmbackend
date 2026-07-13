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

    /** Does this tenant have any live invoice in {@code status} whose due date is before {@code date}? */
    boolean existsByTenantIdAndStatusAndDeletedAtIsNullAndDueDateBefore(
            Long tenantId, BillingStatus status, LocalDate date);

    /** Distinct tenants with a live invoice in {@code status} due before {@code date} — drives dunning. */
    @Query("SELECT DISTINCT b.tenantId FROM BillingRecord b "
            + "WHERE b.deletedAt IS NULL AND b.status = :status AND b.dueDate < :date")
    List<Long> findTenantIdsWithStatusDueBefore(@Param("status") BillingStatus status,
                                                @Param("date") LocalDate date);

    /** Live pay-first upgrade invoices (upgradeToPlan set) for a tenant in {@code status} — used to
     *  supersede an earlier unpaid upgrade order when a new one is requested. */
    List<BillingRecord> findByTenantIdAndUpgradeToPlanIsNotNullAndStatusAndDeletedAtIsNull(
            Long tenantId, BillingStatus status);

    /** Furthest period end across the tenant's live invoices in {@code status} (null if none). Lets the
     *  dunning reactivation extend {@code subscriptionEndDate} to the furthest PAID period regardless of
     *  the order invoices were settled in. */
    @Query("SELECT MAX(b.periodEnd) FROM BillingRecord b "
            + "WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId AND b.status = :status")
    LocalDate findMaxPeriodEndByTenantIdAndStatusAndDeletedAtIsNull(@Param("tenantId") Long tenantId,
                                                                    @Param("status") BillingStatus status);
}