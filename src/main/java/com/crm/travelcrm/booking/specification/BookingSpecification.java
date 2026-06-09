package com.crm.travelcrm.booking.specification;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
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
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) return cb.conjunction();

            String pattern = "%" + keyword.toLowerCase() + "%";

            return cb.or(
                    cb.like(cb.lower(root.get("bookingCode")), pattern),
                    cb.like(cb.lower(root.get("customerName")), pattern),
                    cb.like(cb.lower(root.get("destination")), pattern)
            );
        };
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
            predicates.add(cb.isTrue(root.get("active")));

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
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }
}