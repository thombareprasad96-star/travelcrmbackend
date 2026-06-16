package com.crm.travelcrm.vendor.specification;

import com.crm.travelcrm.vendor.entity.VendorEntity;
import org.springframework.data.jpa.domain.Specification;

public class VendorSpecification {

    private VendorSpecification() {}

    public static Specification<VendorEntity> hasTenant(Long tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<VendorEntity> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<VendorEntity> hasStatus(String status) {
        return (root, query, cb) ->
                status == null || status.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("status"), status);
    }

    public static Specification<VendorEntity> hasType(String type) {
        return (root, query, cb) ->
                type == null || type.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("vendorType"), type);
    }

    public static Specification<VendorEntity> hasPayStatus(String payStatus) {
        return (root, query, cb) ->
                payStatus == null || payStatus.isBlank() ? cb.conjunction()
                        : cb.equal(root.get("payStatus"), payStatus);
    }

    public static Specification<VendorEntity> matchesSearch(String q) {
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