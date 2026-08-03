package com.crm.travelcrm.fleet.specification;

import com.crm.travelcrm.fleet.entity.FleetExpense;
import com.crm.travelcrm.fleet.enums.FleetExpenseType;
import com.crm.travelcrm.fleet.enums.FleetPaidBy;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Filters for {@code GET /api/fleet/expenses}. Tenant + not-deleted are never optional. */
public final class FleetExpenseSpecification {

    private FleetExpenseSpecification() {
    }

    public static Specification<FleetExpense> build(
            Long tenantId, UUID vehiclePublicId, UUID tripPublicId, UUID driverPublicId,
            FleetExpenseType type, FleetPaidBy paidBy, LocalDate fromDate, LocalDate toDate,
            Boolean missingReceipt, String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (vehiclePublicId != null) {
                predicates.add(cb.equal(root.get("vehicle").get("publicId"), vehiclePublicId));
            }
            if (tripPublicId != null) {
                predicates.add(cb.equal(root.get("trip").get("publicId"), tripPublicId));
            }
            if (driverPublicId != null) {
                predicates.add(cb.equal(root.get("driver").get("publicId"), driverPublicId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("expenseType"), type));
            }
            if (paidBy != null) {
                predicates.add(cb.equal(root.get("paidBy"), paidBy));
            }

            // Filtered on DOCUMENT date, not posting date: the user is asking "what did we spend in
            // June", which is a question about receipts, not about which period they were booked to.
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("documentDate"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("documentDate"), toDate));
            }

            // The "chase the missing paperwork" worklist — an accountant's first screen before a
            // period close, and the reason hasReceipt is a real column rather than a derived count.
            if (Boolean.TRUE.equals(missingReceipt)) {
                predicates.add(cb.isFalse(root.get("hasReceipt")));
            }

            if (StringUtils.hasText(search)) {
                String like = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("referenceNumber")), like),
                        cb.like(cb.lower(root.get("vehicle").get("vehicleNumber")), like)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
