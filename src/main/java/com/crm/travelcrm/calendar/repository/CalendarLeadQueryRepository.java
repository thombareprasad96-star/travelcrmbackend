package com.crm.travelcrm.calendar.repository;

import com.crm.travelcrm.lead.entity.Lead;
import org.springframework.data.repository.Repository;

import java.time.LocalDateTime;

/**
 * Read-only, calendar-owned query surface over {@code leads} — the "new leads" KPI. Declared here
 * (a narrow {@link Repository}) so the lead module is untouched. {@code createdAt} is the
 * {@code @CreatedDate LocalDateTime} inherited from {@code BaseEntity}.
 */
public interface CalendarLeadQueryRepository extends Repository<Lead, Long> {

    long countByTenantIdAndCreatedAtBetweenAndDeletedAtIsNull(
            Long tenantId, LocalDateTime from, LocalDateTime to);
}