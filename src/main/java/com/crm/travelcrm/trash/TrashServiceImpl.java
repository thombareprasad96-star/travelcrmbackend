package com.crm.travelcrm.trash;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.event.LeadRestoredEvent;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.master.addon.Addon;
import com.crm.travelcrm.master.airline.Airline;
import com.crm.travelcrm.master.cruise.Cruise;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.sightseeing.Sightseeing;
import com.crm.travelcrm.master.vehicle.VehicleEntity;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.trash.dto.TrashGroupDto;
import com.crm.travelcrm.trash.dto.TrashItemDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generic, registry-driven implementation of the Trash lifecycle. Because every
 * {@link TrashableType} maps to a {@code BaseTenantEntity} (shared {@code publicId},
 * {@code tenantId}, {@code deletedAt}/{@code deletedBy}), all operations are expressed as
 * one parameterized JPQL per entity name — no per-module code.
 *
 * <p><b>Tenant safety</b> — every query carries an explicit {@code tenantId = :tid} predicate
 * (read from {@code TenantContext}) on top of the Hibernate {@code tenantFilter}, so isolation
 * never depends on the filter being active. The purge path is invoked once per tenant by the
 * scheduler with the context set for that tenant.</p>
 *
 * <p><b>Cascades</b> — restore re-publishes {@link LeadRestoredEvent} so a restored lead also
 * un-trashes its quotations (symmetric with the soft-delete cascade). Hard deletes go through
 * {@code EntityManager.remove}, so JPA cascade / orphanRemoval (lead itinerary, element
 * collections, quotation children) fire and nothing is left orphaned.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TrashServiceImpl implements TrashService {

    @PersistenceContext
    private EntityManager em;

    private final TrashProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // ── List ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TrashGroupDto> listTrash() {
        Long tenantId = requireTenantId();
        includeTrashedRows();
        LocalDateTime now = LocalDateTime.now();

        List<TrashGroupDto> groups = new ArrayList<>();
        for (TrashableType type : TrashableType.values()) {
            List<BaseTenantEntity> rows = findTrashedRows(type, tenantId);
            List<TrashItemDto> items = rows.stream()
                    .map(e -> toItem(type, e, now))
                    .toList();
            groups.add(TrashGroupDto.builder()
                    .entityType(type.key())
                    .module(type.module())
                    .count(items.size())
                    .items(items)
                    .build());
        }
        return groups;
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void restore(String entityTypeKey, UUID publicId) {
        Long tenantId = requireTenantId();
        includeTrashedRows();
        TrashableType type = resolveType(entityTypeKey);
        BaseTenantEntity entity = findTrashedRow(type, publicId, tenantId);

        entity.restore();                 // managed entity — flush persists deletedAt/by = null
        em.flush();
        log.info("Restored {} {} from trash | tenantId: {}", type.key(), publicId, tenantId);

        // Cascade: bring the lead's quotations back too (symmetric with soft-delete cascade).
        if (entity instanceof Lead lead) {
            eventPublisher.publishEvent(new LeadRestoredEvent(lead.getId(), tenantId));
        }
    }

    // ── Delete-now (hard) ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteNow(String entityTypeKey, UUID publicId) {
        Long tenantId = requireTenantId();
        includeTrashedRows();
        TrashableType type = resolveType(entityTypeKey);
        BaseTenantEntity entity = findTrashedRow(type, publicId, tenantId);

        em.remove(entity);                // cascades dependents (orphanRemoval / element collections)
        em.flush();
        log.warn("Hard-deleted {} {} from trash | tenantId: {}", type.key(), publicId, tenantId);
    }

    // ── Purge (scheduled, per tenant) ──────────────────────────────────────────

    @Override
    @Transactional
    public void purgeForCurrentTenant(LocalDateTime cutoff) {
        Long tenantId = requireTenantId();
        includeTrashedRows();
        for (TrashableType type : TrashableType.values()) {
            @SuppressWarnings("unchecked")
            List<BaseTenantEntity> expired = em.createQuery(
                            "SELECT e FROM " + type.entityName()
                                    + " e WHERE e.tenantId = :tid AND e.deletedAt IS NOT NULL"
                                    + " AND e.deletedAt < :cutoff")
                    .setParameter("tid", tenantId)
                    .setParameter("cutoff", cutoff)
                    .getResultList();

            for (BaseTenantEntity entity : expired) {
                em.remove(entity);
            }
            if (!expired.isEmpty()) {
                em.flush();
                log.info("Purged {} expired {} record(s) | tenantId: {}",
                        expired.size(), type.key(), tenantId);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<BaseTenantEntity> findTrashedRows(TrashableType type, Long tenantId) {
        return em.createQuery(
                        "SELECT e FROM " + type.entityName()
                                + " e WHERE e.tenantId = :tid AND e.deletedAt IS NOT NULL"
                                + " ORDER BY e.deletedAt DESC")
                .setParameter("tid", tenantId)
                .getResultList();
    }

    private BaseTenantEntity findTrashedRow(TrashableType type, UUID publicId, Long tenantId) {
        List<?> rows = em.createQuery(
                        "SELECT e FROM " + type.entityName()
                                + " e WHERE e.publicId = :pid AND e.tenantId = :tid"
                                + " AND e.deletedAt IS NOT NULL")
                .setParameter("pid", publicId)
                .setParameter("tid", tenantId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No trashed " + type.module() + " record found: " + publicId);
        }
        return (BaseTenantEntity) rows.get(0);
    }

    private TrashItemDto toItem(TrashableType type, BaseTenantEntity e, LocalDateTime now) {
        LocalDateTime purgeAt = e.getDeletedAt() != null
                ? e.getDeletedAt().plusDays(properties.getRetentionDays())
                : null;
        long days = 0;
        if (purgeAt != null) {
            days = Math.max(0, Duration.between(now, purgeAt).toDays());
        }
        return TrashItemDto.builder()
                .entityType(type.key())
                .module(type.module())
                .publicId(e.getPublicId())
                .label(label(e))
                .deletedAt(e.getDeletedAt())
                .deletedBy(e.getDeletedBy())
                .purgeAt(purgeAt)
                .daysUntilPurge(days)
                .build();
    }

    /** One place to derive a human label per trashable entity (shown in the Trash list). */
    private String label(BaseTenantEntity e) {
        if (e instanceof Lead l)         return l.getCustomerName();
        if (e instanceof Customer c)     return c.getName() + " (" + c.getCustomerCode() + ")";
        if (e instanceof Booking b)      return b.getBookingCode() + " — " + b.getCustomerNameSnapshot();
        if (e instanceof Quotation q)    return q.getTitle();
        if (e instanceof Vendor v)       return v.getVendorName() + " (" + v.getVendorCode() + ")";
        if (e instanceof Reminder r)     return r.getTitle();
        if (e instanceof Hotel h)        return h.getName();
        if (e instanceof Airline a)      return a.getName();
        if (e instanceof Cruise c)       return c.getName();
        if (e instanceof Addon a)        return a.getName();
        if (e instanceof Sightseeing s)  return s.getTitle();
        if (e instanceof VehicleEntity v) return v.getName();
        if (e instanceof City c)         return c.getName();
        if (e instanceof Destination d)  return d.getName();
        if (e instanceof Country c)      return c.getName();
        return "Record";
    }

    private TrashableType resolveType(String key) {
        TrashableType type = TrashableType.fromKey(key);
        if (type == null) {
            throw new BusinessException("Unknown trash type: " + key, HttpStatus.BAD_REQUEST);
        }
        return type;
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty for a trash operation.");
        }
        return tenantId;
    }

    /**
     * Master-data entities hide trashed rows behind {@code softDeleteFilter} (enabled per
     * transaction by {@code TenantFilterAspect}); disable it here so the Trash list / restore /
     * delete-now / purge queries can actually see them. A no-op for entity types that never apply
     * the filter (core CRM, Vendor, Reminder use explicit {@code deletedAt} predicates instead).
     */
    private void includeTrashedRows() {
        em.unwrap(Session.class).disableFilter("softDeleteFilter");
    }
}