package com.crm.travelcrm.fleet.specification;

import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** GET /api/fleet/vehicles filter. Always tenant-scoped and excludes soft-deleted rows. */
public final class FleetVehicleSpecification {

    private FleetVehicleSpecification() {
    }

    public static Specification<FleetVehicle> build(
            Long tenantId, FleetVehicleStatus status, FleetOwnerType ownerType, String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (ownerType != null) {
                predicates.add(cb.equal(root.get("ownerType"), ownerType));
            }
            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("vehicleNumber")), like),
                        cb.like(cb.lower(root.get("make")), like),
                        cb.like(cb.lower(root.get("model")), like),
                        cb.like(cb.lower(root.get("type")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}