package com.crm.travelcrm.platform.audit.service;

import com.crm.travelcrm.platform.audit.dto.PlatformAuditLogResponse;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditLog;
import com.crm.travelcrm.platform.audit.repository.PlatformAuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlatformAuditServiceImpl implements PlatformAuditService {

    private final PlatformAuditLogRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformAuditLogResponse> list(PlatformAuditAction action, Boolean success,
                                               LocalDate from, LocalDate to, String q, Pageable pageable) {
        String text = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();
        // Dates are inclusive: from → start of day, to → end of day.
        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt   = to != null ? to.atTime(LocalTime.MAX) : null;

        // Build predicates dynamically — only non-null filters become predicates, so there are no
        // untyped ":param IS NULL" bind markers (Postgres rejects those for temporal/enum params).
        Specification<PlatformAuditLog> spec = (root, cq, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (action != null)  preds.add(cb.equal(root.get("action"), action));
            if (success != null) preds.add(cb.equal(root.get("success"), success));
            if (fromDt != null)  preds.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), fromDt));
            if (toDt != null)    preds.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("createdAt"), toDt));
            if (text != null) {
                String like = "%" + text + "%";
                preds.add(cb.or(
                        cb.like(cb.lower(root.<String>get("actorEmail")), like),
                        cb.like(cb.lower(root.<String>get("description")), like),
                        cb.like(cb.lower(root.<String>get("targetTenantCode")), like)));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };

        return repository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    public List<String> actions() {
        return Arrays.stream(PlatformAuditAction.values()).map(Enum::name).toList();
    }

    private PlatformAuditLogResponse toResponse(PlatformAuditLog l) {
        return new PlatformAuditLogResponse(
                l.getPublicId(),
                l.getAction() != null ? l.getAction().name() : null,
                l.getActorEmail(),
                l.getTargetTenantCode(),
                l.getTargetType(),
                l.getTargetPublicId(),
                l.isSuccess(),
                l.getDescription(),
                l.getIpAddress(),
                l.getCreatedAt());
    }
}
