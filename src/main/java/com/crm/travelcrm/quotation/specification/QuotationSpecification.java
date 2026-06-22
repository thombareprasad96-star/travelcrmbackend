package com.crm.travelcrm.quotation.specification;

import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable predicates for querying {@link Quotation}. Tenant scoping is also applied
 * here explicitly (defence in depth) in addition to the Hibernate {@code tenantFilter}
 * that the {@code TenantFilterAspect} enables on transactional reads.
 */
public final class QuotationSpecification {

    private QuotationSpecification() {}

    /** Live (not soft-deleted) quotations of the given tenant. */
    public static Specification<Quotation> base(Long tenantId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isNull(root.get("deletedAt"))
        );
    }

    /** Free-text search across title, customer name and destination. */
    public static Specification<Quotation> search(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) return cb.conjunction();
            String pattern = "%" + keyword.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("customerName")), pattern),
                    cb.like(cb.lower(root.get("destination")), pattern)
            );
        };
    }

    /** Optional structured filters. Any {@code null} argument is ignored. */
    public static Specification<Quotation> filter(QuotationStage stage,
                                                  UUID leadPublicId,
                                                  LocalDateTime fromDate,
                                                  LocalDateTime toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (stage != null) {
                predicates.add(cb.equal(root.get("stage"), stage));
            }
            if (leadPublicId != null) {
                predicates.add(cb.equal(root.get("leadPublicId"), leadPublicId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toDate));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}