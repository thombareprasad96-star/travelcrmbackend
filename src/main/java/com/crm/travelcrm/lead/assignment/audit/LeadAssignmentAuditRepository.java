package com.crm.travelcrm.lead.assignment.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadAssignmentAuditRepository extends JpaRepository<LeadAssignmentAudit, Long> {

    /** Every assignment-audit row for one lead, newest first (tenant-scoped). */
    List<LeadAssignmentAudit> findByTenantIdAndLeadIdOrderByCreatedAtDesc(Long tenantId, Long leadId);
}