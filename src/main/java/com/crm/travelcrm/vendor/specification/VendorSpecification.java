package com.crm.travelcrm.vendor.specification;

import com.crm.travelcrm.vendor.entity.Vendor;
import org.springframework.data.jpa.domain.Specification;

public class VendorSpecification {

    private VendorSpecification() {}

    public static Specification<Vendor> isActiveTenant(Long tenantId) {
        return Specification.where(hasTenant(tenantId)).and(notDeleted());
    }

    public static Specification<Vendor> hasTenant(Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<Vendor> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Vendor> hasStatus(String status) {
        return (root, query, cb) ->
                status == null || status.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("status"), status);
    }

    public static Specification<Vendor> hasType(String type) {
        return (root, query, cb) ->
                type == null || type.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("vendorType"), type);
    }

    public static Specification<Vendor> hasPayStatus(String payStatus) {
        return (root, query, cb) ->
                payStatus == null || payStatus.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("payStatus"), payStatus);
    }

    public static Specification<Vendor> matchesSearch(String q) {
        return (root, query, cb) -> {
            if (q == null || q.isBlank()) return cb.conjunction();
            String pattern = "%" + q.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("vendorName")), pattern),
                    cb.like(cb.lower(root.get("vendorCode")), pattern),
                    cb.like(cb.lower(root.get("city")),       pattern),
                    cb.like(cb.lower(root.get("email")),      pattern),
                    cb.like(cb.lower(root.get("phone")),      pattern)
            );
        };
    }
}