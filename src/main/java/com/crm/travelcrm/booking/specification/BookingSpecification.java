package com.crm.travelcrm.booking.specification;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.common.util.SearchSpec;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class BookingSpecification {

    private BookingSpecification() {}

    // ── Search (booking code, customer name, destination) ────────────────────

    public static Specification<Booking> search(String keyword) {
        return SearchSpec.contains(keyword, "bookingCode", "customerNameSnapshot", "destinationSnapshot");
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    public static Specification<Booking> filter(
            BookingStatus status,
            PaymentStatus paymentStatus,
            Integer bookingMonth,
            Integer travelMonth,
            Long customerId,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // always filter soft-deleted records
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (paymentStatus != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }

            if (bookingMonth != null) {
                predicates.add(cb.equal(cb.function("MONTH", Integer.class, root.get("bookingDate")), bookingMonth));
            }

            if (travelMonth != null) {
                predicates.add(cb.equal(cb.function("MONTH", Integer.class, root.get("travelDate")), travelMonth));
            }

            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }

            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("bookingDate"), fromDate));
            }

            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("bookingDate"), toDate));
            }

            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("customerAmount"), minAmount));
            }

            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("customerAmount"), maxAmount));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    // ── Active only (reusable base filter) ───────────────────────────────────

    public static Specification<Booking> isActive() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    // ── Owner scope (B2B sub-agent row-level restriction) ────────────────────
    // AND-ed onto list/search/filter specs ONLY when the caller is a sub-agent, so a sub-agent
    // sees just the bookings it owns. Every other role skips this predicate (tenant-wide, unchanged).
    public static Specification<Booking> ownedBy(Long ownerUserId) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ownerUserId);
    }
}