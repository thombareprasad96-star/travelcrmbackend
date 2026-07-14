package com.crm.travelcrm.subagent.repository;

import com.crm.travelcrm.subagent.entity.SubAgentCommission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface SubAgentCommissionRepository extends JpaRepository<SubAgentCommission, Long> {

    /** Currently-accrued commission for a booking = the signed sum of its ledger rows. */
    @Query("""
            SELECT COALESCE(SUM(c.amount), 0) FROM SubAgentCommission c
            WHERE c.bookingId = :bookingId AND c.deletedAt IS NULL
            """)
    BigDecimal sumByBookingId(@Param("bookingId") Long bookingId);

    /** Total commission accrued for one sub-agent within a tenant (for the roll-up summary). */
    @Query("""
            SELECT COALESCE(SUM(c.amount), 0) FROM SubAgentCommission c
            WHERE c.tenantId = :tenantId AND c.subAgentUserId = :subAgentUserId AND c.deletedAt IS NULL
            """)
    BigDecimal sumByTenantAndSubAgent(@Param("tenantId") Long tenantId,
                                      @Param("subAgentUserId") Long subAgentUserId);

    /** Commission accrued per sub-agent across a tenant — one query for the roll-up. Rows: [userId, sum]. */
    @Query("""
            SELECT c.subAgentUserId, COALESCE(SUM(c.amount), 0) FROM SubAgentCommission c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL
            GROUP BY c.subAgentUserId
            """)
    List<Object[]> sumGroupedBySubAgent(@Param("tenantId") Long tenantId);

    /** Ledger rows for one sub-agent (newest first) — the drill-down view. */
    List<SubAgentCommission> findByTenantIdAndSubAgentUserIdAndDeletedAtIsNullOrderByOccurredOnDescIdDesc(
            Long tenantId, Long subAgentUserId);
}
