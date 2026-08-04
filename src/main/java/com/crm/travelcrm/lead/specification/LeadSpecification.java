package com.crm.travelcrm.lead.specification;

import com.crm.travelcrm.common.util.SearchSpec;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Server-side search / filter / scope for the leads LIST endpoint ({@code GET /api/leads}) —
 * mirrors {@code BookingSpecification} so the two flagship lists behave identically. Every
 * predicate is optional and AND-ed together by the service; a null/blank argument contributes
 * nothing (an always-true predicate), so absent filters simply widen the result.
 */
public class LeadSpecification {

    private LeadSpecification() {}

    /**
     * Tenant + soft-delete base. {@code tenantId} is passed EXPLICITLY and never relies on the
     * Hibernate {@code tenantFilter} (which is only active inside a transaction and does not apply
     * on this criteria path). Eager-joins the assignee on the DATA query only — skipped on the
     * COUNT query — to kill the N+1 the mapper would otherwise trigger per row. A ManyToOne fetch
     * is pagination-safe (no in-memory paging, unlike a collection fetch).
     */
    public static Specification<Lead> base(Long tenantId) {
        return (root, query, cb) -> {
            if (query != null
                    && query.getResultType() != Long.class
                    && query.getResultType() != long.class) {
                root.fetch("assignedUser", JoinType.LEFT);
                query.distinct(true);
            }
            return cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.isNull(root.get("deletedAt"))
            );
        };
    }

    /** Case-insensitive "contains" over customer name / phone / email / lead code. */
    public static Specification<Lead> search(String keyword) {
        return SearchSpec.contains(keyword, "customerName", "phone", "email", "leadCode");
    }

    /** Exact stage match. Null ⇒ no stage restriction. */
    public static Specification<Lead> hasStage(LeadStage stage) {
        return (root, query, cb) ->
                stage == null ? cb.conjunction() : cb.equal(root.get("leadStage"), stage);
    }

    /** Exact lead-type (warmth) match — powers the 'Fresh' tab. Null ⇒ no type restriction. */
    public static Specification<Lead> hasLeadType(LeadType leadType) {
        return (root, query, cb) ->
                leadType == null ? cb.conjunction() : cb.equal(root.get("leadType"), leadType);
    }

    /** Inclusive {@code [from, to]} range on the lead's creation date (createdAt). */
    public static Specification<Lead> createdBetween(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay()));
            if (to != null)   ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay()));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * Row-level scope (own / team): {@code assignedUser.id IN (:ids)}. A null collection means
     * "no restriction" (the caller's LEAD_READ scope is ALL); the service handles the empty-set
     * "sees nothing" case before ever building the spec.
     */
    public static Specification<Lead> ownedByIds(Collection<Long> visibleUserIds) {
        return (root, query, cb) ->
                visibleUserIds == null ? cb.conjunction()
                        : root.get("assignedUser").get("id").in(visibleUserIds);
    }
}
