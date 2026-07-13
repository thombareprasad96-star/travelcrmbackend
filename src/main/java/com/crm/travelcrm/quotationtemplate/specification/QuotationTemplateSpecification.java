package com.crm.travelcrm.quotationtemplate.specification;

import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** Composable filters for the template list. Mirrors {@code QuotationSpecification}. */
public final class QuotationTemplateSpecification {

    private QuotationTemplateSpecification() {}

    /** Defence in depth: tenant + live rows, even though the Hibernate filter also scopes tenant. */
    public static Specification<QuotationTemplate> base(Long tenantId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isNull(root.get("deletedAt")));
    }

    public static Specification<QuotationTemplate> search(String keyword) {
        return (root, query, cb) -> {
            if (!StringUtils.hasText(keyword)) return cb.conjunction();
            String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            Predicate onName = cb.like(cb.lower(root.get("name")), like);
            Predicate onDescription = cb.like(cb.lower(root.get("description")), like);
            return cb.or(onName, onDescription);
        };
    }

    public static Specification<QuotationTemplate> active(Boolean active) {
        return (root, query, cb) -> active == null
                ? cb.conjunction()
                : cb.equal(root.get("active"), active);
    }
}