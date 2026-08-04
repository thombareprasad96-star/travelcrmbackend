package com.crm.travelcrm.fleet.specification;

import com.crm.travelcrm.fleet.entity.FleetComplianceDocument;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDocumentStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Filters for {@code GET /api/fleet/documents}. Tenant + not-deleted are never optional. */
public final class FleetDocumentSpecification {

    private FleetDocumentSpecification() {
    }

    public static Specification<FleetComplianceDocument> build(
            Long tenantId, FleetRefType ownerType, UUID vehiclePublicId, UUID driverPublicId,
            FleetDocumentCategory category, String statusFilter, Boolean needsReview,
            LocalDate today, String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (ownerType != null) predicates.add(cb.equal(root.get("ownerType"), ownerType));
            if (category != null) predicates.add(cb.equal(root.get("category"), category));
            if (vehiclePublicId != null) {
                predicates.add(cb.equal(root.get("vehicle").get("publicId"), vehiclePublicId));
            }
            if (driverPublicId != null) {
                predicates.add(cb.equal(root.get("driver").get("publicId"), driverPublicId));
            }

            // The backfill's worklist. 35 rows arrived knowing only an expiry date — the number,
            // issue date and authority were genuinely unknown and were not invented, so this is how
            // someone finds them to fill in.
            if (Boolean.TRUE.equals(needsReview)) {
                predicates.add(cb.isTrue(root.get("needsReview")));
            }

            // ACTIVE / EXPIRING / EXPIRED are DERIVED from the dates, not stored — a stored status
            // that disagrees with its own validUntil is a lie. So those three filter on dates here,
            // while SUPERSEDED and REVOKED (which are decisions) filter on the column.
            if (StringUtils.hasText(statusFilter)) {
                String s = statusFilter.trim().toUpperCase();
                List<FleetDocumentStatus> decided =
                        List.of(FleetDocumentStatus.SUPERSEDED, FleetDocumentStatus.REVOKED);

                switch (s) {
                    case "EXPIRED" -> {
                        predicates.add(root.get("status").in(decided).not());
                        predicates.add(cb.isNotNull(root.get("validUntil")));
                        predicates.add(cb.lessThan(root.get("validUntil"), today));
                    }
                    case "EXPIRING" -> {
                        predicates.add(root.get("status").in(decided).not());
                        predicates.add(cb.isNotNull(root.get("validUntil")));
                        predicates.add(cb.greaterThanOrEqualTo(root.get("validUntil"), today));
                        predicates.add(cb.lessThanOrEqualTo(root.get("validUntil"), today.plusDays(30)));
                    }
                    case "ACTIVE" -> {
                        predicates.add(root.get("status").in(decided).not());
                        predicates.add(cb.or(
                                cb.isNull(root.get("validUntil")),                       // open-ended
                                cb.greaterThan(root.get("validUntil"), today.plusDays(30))));
                    }
                    case "SUPERSEDED" -> predicates.add(
                            cb.equal(root.get("status"), FleetDocumentStatus.SUPERSEDED));
                    case "REVOKED" -> predicates.add(
                            cb.equal(root.get("status"), FleetDocumentStatus.REVOKED));
                    default -> { /* unknown value narrows nothing, same as the rest of this module */ }
                }
            }

            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("documentNumber")), like),
                        cb.like(cb.lower(root.get("issuingAuthority")), like),
                        cb.like(cb.lower(root.get("vehicle").get("vehicleNumber")), like),
                        cb.like(cb.lower(root.get("driver").get("name")), like)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
