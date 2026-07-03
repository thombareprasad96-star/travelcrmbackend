package com.crm.travelcrm.fleet.specification;

import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/fleet/trips filter. Vehicle/driver filters go through the to-one associations
 * (implicit inner join — safe for paging). Date range applies to the trip start.
 */
public final class FleetTripSpecification {

    private FleetTripSpecification() {
    }

    public static Specification<FleetTrip> build(
            Long tenantId, UUID vehiclePublicId, UUID driverPublicId, FleetTripStatus status,
            UUID bookingPublicId, LocalDate fromDate, LocalDate toDate, String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (vehiclePublicId != null) {
                predicates.add(cb.equal(root.get("vehicle").get("publicId"), vehiclePublicId));
            }
            if (driverPublicId != null) {
                predicates.add(cb.equal(root.get("driver").get("publicId"), driverPublicId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (bookingPublicId != null) {
                predicates.add(cb.equal(root.get("bookingPublicId"), bookingPublicId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startDatetime"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                predicates.add(cb.lessThan(root.get("startDatetime"), toDate.plusDays(1).atStartOfDay()));
            }
            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("routeFrom")), like),
                        cb.like(cb.lower(root.get("routeTo")), like),
                        cb.like(cb.lower(root.get("purpose")), like),
                        cb.like(cb.lower(root.get("bookingCode")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}