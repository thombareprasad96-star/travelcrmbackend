package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetDocumentRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDocumentResponseDto;
import com.crm.travelcrm.fleet.entity.*;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDocumentStatus;
import com.crm.travelcrm.fleet.enums.FleetRefType;
import com.crm.travelcrm.fleet.repository.*;
import com.crm.travelcrm.fleet.specification.FleetDocumentSpecification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Compliance documents: the papers a check-post, an RTO or a border post asks for.
 *
 * <p>Three rules define this service:
 * <ol>
 *   <li><b>Renewal never overwrites.</b> It inserts a new row and marks the old one superseded. The
 *       previous number, authority, validity and cost survive, because "what was valid on this past
 *       date" is a scrutiny question and the old four-date-column shape could not answer it.</li>
 *   <li><b>Checks run against the trip's RETURN date, not today.</b> A permit valid tomorrow but
 *       expired on day six of a Char Dham run is a vehicle impounded at a barrier — and "valid
 *       today" would cheerfully have let it leave.</li>
 *   <li><b>Warn by default, block by exception.</b> A dispatcher was emphatic that compliance must
 *       never block; an accountant was equally emphatic that an expired PSV badge must refuse the
 *       assignment. The paper decides, and an owner can override per document.</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetComplianceServiceImpl implements FleetComplianceService {

    private final FleetComplianceDocumentRepository documentRepository;
    private final FleetVehicleRepository vehicleRepository;
    private final FleetDriverRepository driverRepository;
    private final FleetExpenseRepository expenseRepository;
    private final TenantTimeZone tenantTimeZone;

    /** Reuses the existing fleet expiry window so the dashboard and this agree on "expiring". */
    @Value("${app.fleet.dashboard-expiry-window-days:30}")
    private int warnWithinDays;

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetDocumentResponseDto create(FleetDocumentRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetComplianceDocument doc = build(request, tenantId);
        FleetComplianceDocument saved = documentRepository.save(doc);

        log.info("Fleet document created | id: {} | {} | owner: {} | tenantId: {}",
                saved.getId(), saved.getCategory(), saved.getOwnerType(), tenantId);
        return toDto(saved);
    }

    /**
     * Renew: insert the replacement, mark the original superseded.
     *
     * <p>The two rows are linked, so the history reads as a chain rather than as a pile of
     * unrelated certificates. Nothing about the old row is edited except its status — its dates and
     * number are the record of what was valid then.
     */
    @Override
    @Transactional
    public FleetDocumentResponseDto renew(UUID publicId, FleetDocumentRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetComplianceDocument original = findOrThrow(publicId, tenantId);

        if (original.getStatus() == FleetDocumentStatus.SUPERSEDED) {
            throw new BusinessException(
                    "This one has already been renewed — renew the current document instead",
                    HttpStatus.CONFLICT);
        }
        if (request.getValidUntil() == null) {
            throw new BusinessException(
                    "A renewal needs the new validity date", HttpStatus.BAD_REQUEST);
        }

        // The replacement inherits the owner and category: a renewal of an insurance policy is still
        // that vehicle's insurance, and letting either be re-specified is how a renewal quietly
        // lands on the wrong asset.
        FleetComplianceDocument renewal = build(request, tenantId);
        renewal.setOwnerType(original.getOwnerType());
        renewal.setVehicle(original.getVehicle());
        renewal.setDriver(original.getDriver());
        renewal.setCategory(original.getCategory());
        renewal.setSupersedes(original);
        FleetComplianceDocument saved = documentRepository.save(renewal);

        original.setStatus(FleetDocumentStatus.SUPERSEDED);
        documentRepository.save(original);

        log.info("Fleet document renewed | {} → {} | {} | tenantId: {}",
                original.getId(), saved.getId(), saved.getCategory(), tenantId);
        return toDto(saved);
    }

    @Override
    @Transactional
    public FleetDocumentResponseDto revoke(UUID publicId, String reason) {
        Long tenantId = FleetContext.tenantId();
        FleetComplianceDocument doc = findOrThrow(publicId, tenantId);

        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("A revocation needs a reason", HttpStatus.BAD_REQUEST);
        }
        doc.setStatus(FleetDocumentStatus.REVOKED);
        doc.setRevokedAt(tenantTimeZone.now());
        doc.setRevokeReason(reason.trim());
        documentRepository.save(doc);

        log.info("Fleet document revoked | id: {} | reason: {}", doc.getId(), reason);
        return toDto(doc);
    }

    @Override
    @Transactional
    public FleetDocumentResponseDto update(UUID publicId, FleetDocumentRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetComplianceDocument doc = findOrThrow(publicId, tenantId);

        if (!doc.getStatus().isCurrent()) {
            throw new BusinessException(
                    "A superseded or revoked document is history — it cannot be edited. Renew the "
                            + "current one instead.",
                    HttpStatus.CONFLICT);
        }
        doc.setDocumentNumber(request.getDocumentNumber());
        doc.setIssuingAuthority(request.getIssuingAuthority());
        doc.setStateCode(request.getStateCode());
        doc.setBorderPost(request.getBorderPost());
        doc.setIssuedOn(request.getIssuedOn());
        doc.setValidFrom(request.getValidFrom());
        doc.setValidUntil(request.getValidUntil());
        doc.setExitDeadline(request.getExitDeadline());
        doc.setBlocking(request.getBlocking());
        doc.setNotes(request.getNotes());
        // Filling in what the backfill could not know is exactly how a row stops needing review.
        if (StringUtils.hasText(request.getDocumentNumber())) doc.setNeedsReview(false);

        return toDto(documentRepository.save(doc));
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = FleetContext.tenantId();
        FleetComplianceDocument doc = findOrThrow(publicId, tenantId);
        // Soft-delete only, and this table is NOT registered in TrashableType: the 30-day purge
        // would hard-delete a statutory record whose retention requirement is eight years.
        doc.softDelete(FleetContext.username());
        documentRepository.save(doc);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<FleetDocumentResponseDto> forVehicle(UUID vehiclePublicId) {
        return documentRepository
                .findByTenantIdAndVehicle_PublicIdAndDeletedAtIsNullOrderByValidUntilDesc(
                        FleetContext.tenantId(), vehiclePublicId)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetDocumentResponseDto> forDriver(UUID driverPublicId) {
        return documentRepository
                .findByTenantIdAndDriver_PublicIdAndDeletedAtIsNullOrderByValidUntilDesc(
                        FleetContext.tenantId(), driverPublicId)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FleetDocumentResponseDto> expiring(Integer withinDays) {
        Long tenantId = FleetContext.tenantId();
        LocalDate today = tenantTimeZone.todayFor(tenantId);
        int window = withinDays != null ? withinDays : warnWithinDays;
        return documentRepository.findExpiringBy(tenantId, today.plusDays(window))
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetDocumentResponseDto> list(
            String ownerType, UUID vehicleId, UUID driverId, String category,
            String status, Boolean needsReview, String search, int page, int size) {

        Long tenantId = FleetContext.tenantId();
        var spec = FleetDocumentSpecification.build(tenantId,
                parse(FleetRefType.class, ownerType), vehicleId, driverId,
                parse(FleetDocumentCategory.class, category), status, needsReview,
                tenantTimeZone.todayFor(tenantId), search);

        // Soonest-to-lapse first: this screen exists to show what needs attention next, and a
        // document with no expiry at all (a lifetime registration) belongs at the bottom. Postgres
        // sorts NULLs last on ASC by default, which is exactly that.
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "validUntil").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<FleetComplianceDocument> result = documentRepository.findAll(spec, pageable);
        return PagedApiResponse.of("Documents fetched", result.map(this::toDto).getContent(),
                PaginationMeta.from(result));
    }

    /** Unknown filter values narrow nothing, same as the rest of this module. */
    private <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ComplianceCheck check(UUID vehiclePublicId, UUID driverPublicId, LocalDate throughDate) {
        Long tenantId = FleetContext.tenantId();
        LocalDate through = throughDate != null ? throughDate : tenantTimeZone.todayFor(tenantId);

        List<FleetDocumentResponseDto> warnings = new ArrayList<>();
        List<FleetDocumentResponseDto> blockers = new ArrayList<>();

        if (vehiclePublicId != null) {
            FleetVehicle v = vehicleRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(vehiclePublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found: " + vehiclePublicId));
            collectFailures(documentRepository.findCurrentForVehicle(tenantId, v.getId()),
                    through, warnings, blockers);
        }
        if (driverPublicId != null) {
            FleetDriver d = driverRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(driverPublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Driver not found: " + driverPublicId));
            collectFailures(documentRepository.findCurrentForDriver(tenantId, d.getId()),
                    through, warnings, blockers);
        }
        return new ComplianceCheck(blockers.isEmpty(), through, blockers, warnings);
    }

    /**
     * Splits failures into blockers and warnings.
     *
     * <p>Checked against {@code through} — the trip's return date — not today. That is the whole
     * point: a permit that lapses on day six of an eleven-day run passes every "is it valid now"
     * test right up until the barrier.
     */
    private void collectFailures(List<FleetComplianceDocument> docs, LocalDate through,
                                 List<FleetDocumentResponseDto> warnings,
                                 List<FleetDocumentResponseDto> blockers) {
        for (FleetComplianceDocument doc : docs) {
            if (doc.isValidThrough(through)) continue;
            (doc.isBlocking() ? blockers : warnings).add(toDto(doc));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetComplianceDocument findOrThrow(UUID publicId, Long tenantId) {
        return documentRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + publicId));
    }

    private FleetComplianceDocument build(FleetDocumentRequestDto request, Long tenantId) {
        boolean hasVehicle = request.getVehiclePublicId() != null;
        boolean hasDriver = request.getDriverPublicId() != null;

        // Enforced here AND by a CHECK constraint. A polymorphic owner with two nullable columns is
        // only safe while something says so.
        if (hasVehicle == hasDriver) {
            throw new BusinessException(
                    "A document belongs to a vehicle or to a driver — exactly one",
                    HttpStatus.BAD_REQUEST);
        }
        FleetDocumentCategory category = request.getCategory();
        if (hasVehicle && !category.appliesToVehicle()) {
            throw new BusinessException(
                    category.label() + " is a driver document", HttpStatus.BAD_REQUEST);
        }
        if (hasDriver && !category.appliesToDriver()) {
            throw new BusinessException(
                    category.label() + " is a vehicle document", HttpStatus.BAD_REQUEST);
        }
        if (category.needsState() && !StringUtils.hasText(request.getStateCode())) {
            throw new BusinessException(
                    category.label() + " is issued by a state — say which one, because a permit for "
                            + "one state is not a permit for another",
                    HttpStatus.BAD_REQUEST);
        }
        if (category.needsExitDeadline() && request.getExitDeadline() == null) {
            throw new BusinessException(
                    "A Bhansar entry needs the date the vehicle must be back across the border — "
                            + "overstaying is a fine, and the validity date does not say it",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.getValidFrom() != null && request.getValidUntil() != null
                && request.getValidUntil().isBefore(request.getValidFrom())) {
            throw new BusinessException("Valid-until cannot be before valid-from", HttpStatus.BAD_REQUEST);
        }

        var builder = FleetComplianceDocument.builder()
                .category(category)
                .documentNumber(trim(request.getDocumentNumber()))
                .issuingAuthority(trim(request.getIssuingAuthority()))
                .countryCode(StringUtils.hasText(request.getCountryCode())
                        ? request.getCountryCode().toUpperCase() : category.countryCode())
                .stateCode(trim(request.getStateCode()))
                .borderPost(trim(request.getBorderPost()))
                .issuedOn(request.getIssuedOn())
                .validFrom(request.getValidFrom())
                .validUntil(request.getValidUntil())
                .exitDeadline(request.getExitDeadline())
                .blocking(request.getBlocking())
                .notes(request.getNotes());

        if (hasVehicle) {
            builder.ownerType(FleetRefType.VEHICLE)
                    .vehicle(vehicleRepository
                            .findByPublicIdAndTenantIdAndDeletedAtIsNull(request.getVehiclePublicId(), tenantId)
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "Vehicle not found: " + request.getVehiclePublicId())));
        } else {
            builder.ownerType(FleetRefType.DRIVER)
                    .driver(driverRepository
                            .findByPublicIdAndTenantIdAndDeletedAtIsNull(request.getDriverPublicId(), tenantId)
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "Driver not found: " + request.getDriverPublicId())));
        }
        if (request.getExpensePublicId() != null) {
            builder.expense(expenseRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(request.getExpensePublicId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Expense not found: " + request.getExpensePublicId())));
        }
        return builder.build();
    }

    private String trim(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private FleetDocumentResponseDto toDto(FleetComplianceDocument d) {
        LocalDate today = tenantTimeZone.todayFor(d.getTenantId());
        FleetDocumentStatus derived = d.deriveStatus(today, warnWithinDays);

        FleetDocumentResponseDto dto = new FleetDocumentResponseDto();
        dto.setPublicId(d.getPublicId());
        dto.setOwnerType(d.getOwnerType());
        if (d.getVehicle() != null) {
            dto.setVehiclePublicId(d.getVehicle().getPublicId());
            dto.setVehicleNumber(d.getVehicle().getVehicleNumber());
        }
        if (d.getDriver() != null) {
            dto.setDriverPublicId(d.getDriver().getPublicId());
            dto.setDriverName(d.getDriver().getName());
        }
        dto.setCategory(d.getCategory());
        dto.setCategoryLabel(d.getCategory().label());
        dto.setDocumentNumber(d.getDocumentNumber());
        dto.setIssuingAuthority(d.getIssuingAuthority());
        dto.setCountryCode(d.getCountryCode());
        dto.setStateCode(d.getStateCode());
        dto.setBorderPost(d.getBorderPost());
        dto.setIssuedOn(d.getIssuedOn());
        dto.setValidFrom(d.getValidFrom());
        dto.setValidUntil(d.getValidUntil());
        dto.setExitDeadline(d.getExitDeadline());
        dto.setStatus(derived);
        dto.setStatusLabel(derived.label());
        dto.setDaysLeft(d.getValidUntil() == null ? null : ChronoUnit.DAYS.between(today, d.getValidUntil()));
        dto.setExitDaysLeft(d.getExitDeadline() == null ? null : ChronoUnit.DAYS.between(today, d.getExitDeadline()));
        dto.setBlocking(d.isBlocking());
        dto.setSupersedesPublicId(d.getSupersedes() == null ? null : d.getSupersedes().getPublicId());
        dto.setExpensePublicId(d.getExpense() == null ? null : d.getExpense().getPublicId());
        dto.setNeedsReview(d.isNeedsReview());
        dto.setRevokeReason(d.getRevokeReason());
        dto.setNotes(d.getNotes());
        return dto;
    }
}
