package com.crm.travelcrm.lead.assignment.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadAssignmentEventRepository extends JpaRepository<LeadAssignmentEvent, Long> {

    /**
     * The full ownership timeline for one lead, OLDEST first — a trail reads forwards
     * ("auto-assigned → claimed → contacted"), unlike the newest-first audit list.
     *
     * <p>Ordered by {@code id} rather than {@code createdAt}: several events can share a timestamp
     * to the second (a claim immediately followed by a contact), and {@code createdAt} alone would
     * order them arbitrarily — showing "contacted" before the claim that preceded it.
     */
    List<LeadAssignmentEvent> findByTenantIdAndLeadIdAndDeletedAtIsNullOrderByIdAsc(
            Long tenantId, Long leadId);
}
