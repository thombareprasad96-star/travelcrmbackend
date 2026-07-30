package com.crm.travelcrm.customer.specification;

import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable, composable predicates for the dynamic {@code /api/customers/filter}
 * endpoint. Each builder returns a tenant-agnostic fragment; the mandatory
 * tenant + soft-delete guard is supplied by {@link #activeForTenant(Long)} and the
 * service ANDs everything together. Keeping these as small static factories keeps
 * the service readable and the predicates independently testable.
 */
public final class CustomerSpecification {

    private CustomerSpecification() {}

    /** Base scope every query must include: tenant isolation + not soft-deleted. */
    public static Specification<Customer> activeForTenant(Long tenantId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.isNull(root.get("deletedAt"))
        );
    }

    public static Specification<Customer> hasStatus(CustomerStatus status) {
        return (root, query, cb) ->
                status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    public static Specification<Customer> hasType(CustomerType type) {
        return (root, query, cb) ->
                type == null ? cb.conjunction() : cb.equal(root.get("type"), type);
    }

    public static Specification<Customer> hasTier(LoyaltyTier tier) {
        return (root, query, cb) ->
                tier == null ? cb.conjunction() : cb.equal(root.get("tier"), tier);
    }

    /**
     * Free-text search across the columns a user actually types into the list's search box.
     * A blank query contributes {@code conjunction()} (always-true) so the caller can AND this in
     * unconditionally instead of branching.
     */
    public static Specification<Customer> matchesSearch(String q) {
        return (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction();
            String pattern = "%" + q.toLowerCase().trim() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("name")),         pattern),
                    cb.like(cb.lower(root.get("customerCode")), pattern),
                    cb.like(cb.lower(root.get("phone")),        pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("email"), "")), pattern),
                    cb.like(cb.lower(cb.coalesce(root.get("city"),  "")), pattern)
            );
        };
    }

    // ── Owner scope (B2B sub-agent row-level restriction) ────────────────────
    // AND-ed on ONLY when the caller is a sub-agent; other roles skip it (tenant-wide, unchanged).
    public static Specification<Customer> ownedBy(Long ownerUserId) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ownerUserId);
    }
}