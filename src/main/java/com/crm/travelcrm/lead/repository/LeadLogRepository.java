package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.lead.entity.LeadLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadLogRepository extends JpaRepository<LeadLog, Long> {

    /**
     * All non-deleted logs for a lead, newest first. Tenant scope is already guaranteed because
     * the parent lead is resolved under tenant + row-level scope before this is ever called.
     */
    List<LeadLog> findByLead_IdAndDeletedAtIsNullOrderByCreatedAtDesc(long leadId);

    long countByLead_IdAndDeletedAtIsNull(long leadId);

    /** One non-deleted log by publicId that belongs to the given lead (ownership check for delete). */
    Optional<LeadLog> findByPublicIdAndLead_IdAndDeletedAtIsNull(UUID publicId, long leadId);

    /**
     * Every non-deleted log in the tenant (whose lead is also live), newest first, with the lead
     * and its assignee eagerly fetched. The service groups these per lead and applies the caller's
     * row-level scope + search/stage/user filters in memory for the All-Lead-Logs grid.
     */
    @Query("SELECT ll FROM LeadLog ll " +
           "JOIN FETCH ll.lead l LEFT JOIN FETCH l.assignedUser " +
           "WHERE ll.tenantId = :tenantId AND ll.deletedAt IS NULL AND l.deletedAt IS NULL " +
           "ORDER BY ll.createdAt DESC")
    List<LeadLog> findAllForTenantWithLead(@Param("tenantId") Long tenantId);
}