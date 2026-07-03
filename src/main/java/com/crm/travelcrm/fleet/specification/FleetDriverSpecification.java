package com.crm.travelcrm.fleet.specification;

import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** GET /api/fleet/drivers filter. Always tenant-scoped and excludes soft-deleted rows. */
public final class FleetDriverSpecification {

    private FleetDriverSpecification() {
    }

    public static Specification<FleetDriver> build(
            Long tenantId, FleetDriverStatus status, String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("licenseNumber")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}