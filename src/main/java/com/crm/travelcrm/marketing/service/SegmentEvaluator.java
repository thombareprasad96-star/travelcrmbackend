package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.CommunicationPreference;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.crm.travelcrm.marketing.dto.SegmentConditionDTO;
import com.crm.travelcrm.marketing.enums.ConditionOperator;
import com.crm.travelcrm.marketing.enums.SegmentMatchType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Translates a segment's rule set into a tenant-scoped {@link Specification} over
 * {@link Customer}. Always ANDs {@code tenant_id} + {@code deleted_at IS NULL} so a segment
 * can never read across tenants or include trashed customers. Incomplete conditions (an
 * operator that needs a value but has none) are skipped, so a half-built rule in the live
 * preview never errors.
 */
@Component
public class SegmentEvaluator {

    private static final Set<ConditionOperator> NO_VALUE = EnumSet.of(ConditionOperator.IS_SET, ConditionOperator.IS_NOT_SET);

    public Specification<Customer> toSpecification(Long tenantId, SegmentMatchType matchType,
                                                   List<SegmentConditionDTO> conditions) {
        SegmentMatchType match = matchType == null ? SegmentMatchType.ALL : matchType;
        List<SegmentConditionDTO> conds = conditions == null ? List.of() : conditions;

        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("tenantId"), tenantId));
            all.add(cb.isNull(root.get("deletedAt")));

            List<Predicate> rules = new ArrayList<>();
            for (SegmentConditionDTO c : conds) {
                Predicate p = predicateFor(c, root, cb);
                if (p != null) rules.add(p);
            }
            if (!rules.isEmpty()) {
                Predicate[] arr = rules.toArray(new Predicate[0]);
                all.add(match == SegmentMatchType.ANY ? cb.or(arr) : cb.and(arr));
            }
            return cb.and(all.toArray(new Predicate[0]));
        };
    }

    private Predicate predicateFor(SegmentConditionDTO c, jakarta.persistence.criteria.Root<Customer> root, CriteriaBuilder cb) {
        if (c == null || c.field() == null || c.operator() == null) return null;
        ConditionOperator op = c.operator();
        Object val = c.value();
        if (!NO_VALUE.contains(op) && isEmpty(val)) return null;   // skip half-built conditions

        return switch (c.field()) {
            case "loyaltyTier" -> enumPred(root.get("tier"), LoyaltyTier.class, op, val, cb);
            case "customerType" -> enumPred(root.get("type"), CustomerType.class, op, val, cb);
            case "status" -> enumPred(root.get("status"), CustomerStatus.class, op, val, cb);
            case "commPref" -> enumPred(root.get("commPref"), CommunicationPreference.class, op, val, cb);
            case "city" -> stringPred(root.get("city"), op, val, cb);
            case "state" -> stringPred(root.get("state"), op, val, cb);
            case "email" -> stringPred(root.get("email"), op, val, cb);
            case "birthdayMonth" -> monthPred(root.get("birthday"), op, val, cb);
            case "anniversaryMonth" -> monthPred(root.get("anniversary"), op, val, cb);
            case "createdAt" -> datePred(root.get("createdAt"), op, val, cb);
            default -> throw new BusinessException("Unknown segment field: " + c.field(), HttpStatus.BAD_REQUEST);
        };
    }

    // ── per-type predicate builders ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private <E extends Enum<E>> Predicate enumPred(Path<?> rawPath, Class<E> cls, ConditionOperator op, Object val, CriteriaBuilder cb) {
        Path<E> path = (Path<E>) rawPath;
        return switch (op) {
            case EQUALS -> cb.equal(path, parseEnum(cls, val));
            case NOT_EQUALS -> cb.notEqual(path, parseEnum(cls, val));
            case IN -> path.in(parseEnumList(cls, val));
            case NOT_IN -> cb.not(path.in(parseEnumList(cls, val)));
            default -> throw unsupported(op, "an enum field");
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate stringPred(Path<?> rawPath, ConditionOperator op, Object val, CriteriaBuilder cb) {
        Path<String> path = (Path<String>) rawPath;
        return switch (op) {
            case EQUALS -> cb.equal(cb.lower(path), asString(val).toLowerCase());
            case NOT_EQUALS -> cb.notEqual(cb.lower(path), asString(val).toLowerCase());
            case CONTAINS -> cb.like(cb.lower(path), "%" + asString(val).toLowerCase() + "%");
            case IS_SET -> cb.and(cb.isNotNull(path), cb.notEqual(cb.trim(path), ""));
            case IS_NOT_SET -> cb.or(cb.isNull(path), cb.equal(cb.trim(path), ""));
            default -> throw unsupported(op, "a text field");
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate monthPred(Path<?> rawPath, ConditionOperator op, Object val, CriteriaBuilder cb) {
        Path<LocalDate> path = (Path<LocalDate>) rawPath;
        // Postgres date_part('month', <date>) → double precision (unambiguous, unlike month()).
        Expression<Double> month = cb.function("date_part", Double.class, cb.literal("month"), path);
        return switch (op) {
            case EQUALS -> cb.equal(month, (double) asInt(val));
            case IN -> month.in(asList(val).stream().map(x -> (double) asInt(x)).toList());
            default -> throw unsupported(op, "a month field");
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate datePred(Path<?> rawPath, ConditionOperator op, Object val, CriteriaBuilder cb) {
        Path<LocalDateTime> path = (Path<LocalDateTime>) rawPath;
        return switch (op) {
            case GREATER_THAN -> cb.greaterThan(path, parseDate(asString(val)).atStartOfDay());
            case LESS_THAN -> cb.lessThan(path, parseDate(asString(val)).atStartOfDay());
            case BETWEEN -> {
                List<Object> b = asList(val);
                if (b.size() < 2) throw new BusinessException("BETWEEN needs a start and end date.", HttpStatus.BAD_REQUEST);
                yield cb.between(path, parseDate(asString(b.get(0))).atStartOfDay(), parseDate(asString(b.get(1))).atTime(LocalTime.MAX));
            }
            default -> throw unsupported(op, "a date field");
        };
    }

    // ── value coercion ───────────────────────────────────────────────────────────

    private <E extends Enum<E>> E parseEnum(Class<E> cls, Object v) {
        String s = asString(v);
        for (E e : cls.getEnumConstants()) {
            if (e.name().equalsIgnoreCase(s)) return e;
        }
        throw new BusinessException("Invalid value '" + s + "' for " + cls.getSimpleName(), HttpStatus.BAD_REQUEST);
    }

    private <E extends Enum<E>> List<E> parseEnumList(Class<E> cls, Object v) {
        return asList(v).stream().map(x -> parseEnum(cls, x)).toList();
    }

    private LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BusinessException("Invalid date '" + s + "' (expected yyyy-MM-dd).", HttpStatus.BAD_REQUEST);
        }
    }

    private String asString(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(asString(v));
        } catch (Exception e) {
            throw new BusinessException("Expected a number but got: " + v, HttpStatus.BAD_REQUEST);
        }
    }

    private List<Object> asList(Object v) {
        if (v instanceof List<?> l) return new ArrayList<>(l);
        if (v == null) return List.of();
        return List.of(v);
    }

    private boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof List<?> l) return l.isEmpty();
        return false;
    }

    private BusinessException unsupported(ConditionOperator op, String kind) {
        return new BusinessException("Operator " + op + " is not supported on " + kind + ".", HttpStatus.BAD_REQUEST);
    }
}