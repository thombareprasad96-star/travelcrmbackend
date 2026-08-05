package com.crm.travelcrm.lead.specification;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.enums.LeadType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Server-side filtering for the leads list, mirroring {@code BookingSpecification}.
 *
 * <p><b>Why this exists.</b> {@code AllLeads.jsx} sends {@code search}, {@code stage},
 * {@code leadType}, {@code fromDate} and {@code toDate} on {@code GET /api/leads}. The controller
 * declared none of them, so Spring silently discarded all five: the UI looked like it had filtered
 * and had not, and every "narrowing" was really just page 0 of the unfiltered list.
 *
 * <p>Tenant scope and soft-delete are baked in here rather than left to the Hibernate
 * {@code tenantFilter}, because a {@code Specification} query must carry its own tenant predicate to
 * be safe regardless of whether the filter happens to be enabled on the session.
 */
public final class LeadSpecification {

    private LeadSpecification() {
    }

    /**
     * Back-compatible overload without the two work-queue filters. Kept so callers that only ever
     * narrow by stage/type/date do not have to pass two nulls.
     */
    public static Specification<Lead> filter(Long tenantId,
                                             Collection<Long> visibleUserIds,
                                             String search,
                                             LeadStage stage,
                                             LeadType leadType,
                                             LocalDate fromDate,
                                             LocalDate toDate) {
        return filter(tenantId, visibleUserIds, search, stage, leadType, fromDate, toDate,
                null, null);
    }

    /**
     * The full predicate for one list request. Every argument except {@code tenantId} is optional —
     * a null (or blank) value contributes no predicate at all, so the unfiltered call returns
     * exactly what {@code findAllByTenantIdAndDeletedAtIsNull} used to.
     *
     * @param visibleUserIds row-level scope: {@code null} means unrestricted (see
     *                       {@code LeadScopeResolver}); a non-null collection restricts to those
     *                       assignees. An EMPTY collection is never passed here — the caller
     *                       short-circuits to an empty page, because {@code IN ()} is not valid SQL.
     * @param activeOnly     {@code TRUE} restricts to open leads — everything that is not
     *                       {@link LeadStageGroups#TERMINAL_STAGES}. Derived as the complement so a
     *                       stage added to the enum later counts as active here without a change,
     *                       exactly as {@code LeadStageGroups} defines it and as
     *                       {@code getStatsSummary} computes the Active card. Null/false = no
     *                       restriction; it is deliberately NOT combined with {@code stage}, so
     *                       sending both is an intersection, not a conflict.
     * @param followUpDueBy  when non-null, keeps only leads carrying a {@code followUpDate} on or
     *                       before this date — the "Follow-ups" work queue (overdue + due today).
     *                       Leads with no follow-up date are excluded, which is the point: a lead
     *                       nobody promised to call back is not owed a call today.
     */
    public static Specification<Lead> filter(Long tenantId,
                                             Collection<Long> visibleUserIds,
                                             String search,
                                             LeadStage stage,
                                             LeadType leadType,
                                             LocalDate fromDate,
                                             LocalDate toDate,
                                             Boolean activeOnly,
                                             LocalDate followUpDueBy) {
        return (root, query, cb) -> {
            // Replaces the @EntityGraph the old derived finders carried: the mapper builds a UserDto
            // from assignedUser for every row, so without this the page costs one extra query per
            // lead. Skipped for the count query, where a fetch join is illegal.
            if (query != null && Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("assignedUser", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (visibleUserIds != null) {
                predicates.add(root.get("assignedUser").get("id").in(visibleUserIds));
            }
            if (stage != null) {
                predicates.add(cb.equal(root.get("leadStage"), stage));
            }
            if (leadType != null) {
                predicates.add(cb.equal(root.get("leadType"), leadType));
            }

            // The two work-queue filters. Both exist because the leads list has tabs the dashboard
            // cards click into ("Active", "Follow-ups") that are NOT stages — before this they were
            // sent as stage=Active, which LeadStage.fromValue throws on, i.e. a 400.
            if (Boolean.TRUE.equals(activeOnly)) {
                predicates.add(cb.not(root.get("leadStage").in(LeadStageGroups.TERMINAL_STAGES)));
            }
            if (followUpDueBy != null) {
                predicates.add(cb.and(
                        cb.isNotNull(root.get("followUpDate")),
                        cb.lessThanOrEqualTo(root.get("followUpDate"), followUpDueBy)));
            }

            // The date filter is the list's "Added" column, i.e. createdAt. Inclusive at both ends:
            // toDate is widened to the END of that day, or a same-day from/to range would match only
            // rows created at exactly 00:00:00.
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), toDate.plusDays(1).atStartOfDay()));
            }

            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("customerName")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("leadCode")), like)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Resolve the wire value of a stage. The API speaks {@code LeadStage}'s {@code displayName}
     * ("New Lead"), NOT the enum constant — so this goes through {@code fromValue}, which accepts
     * either form case-insensitively. Blank means "no filter"; an unrecognised value throws, which
     * the global handler turns into a 400 rather than silently returning the unfiltered list.
     */
    public static LeadStage parseStage(String raw) {
        return (raw == null || raw.isBlank()) ? null : LeadStage.fromValue(raw.trim());
    }

    /** As {@link #parseStage}, for {@code LeadType} ("Fresh", "Hot", …). */
    public static LeadType parseType(String raw) {
        return (raw == null || raw.isBlank()) ? null : LeadType.fromValue(raw.trim());
    }
}
