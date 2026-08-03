package com.crm.travelcrm.hotelmarketplace.commission.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionEntryDto;
import com.crm.travelcrm.hotelmarketplace.commission.dto.CommissionSummaryDto;
import com.crm.travelcrm.hotelmarketplace.commission.entity.PlatformCommissionEntry;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType;
import com.crm.travelcrm.hotelmarketplace.commission.mapper.CommissionEntryMapper;
import com.crm.travelcrm.hotelmarketplace.commission.repository.PlatformCommissionEntryRepository;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform earning ledger (design doc §5.12, §8 Step 9, §9, §13).
 *
 * <p><b>Append-only.</b> Every method that changes what the platform has earned writes a new row. The
 * only column any method mutates on an existing row is {@code status}, which says how real an amount
 * is — never what the amount was. That is not fastidiousness: revenue that can be restated in place
 * cannot be reconciled against anything previously reported from it, and a cancellation restates the
 * booking these figures were derived from.</p>
 *
 * <p><b>Idempotent by reference key.</b> Each write probes {@code reference_key} first. The approval
 * path is replayed by {@code MarketplaceCrmSyncScheduler}, and a replay that accrued a second time
 * would overstate platform revenue permanently — nothing downstream could tell the duplicate from a
 * legitimate second entry, because both look like valid rows for the same booking.</p>
 *
 * <p><b>SuperAdmin-only.</b> Every row carries {@code supplierTotal} and the platform's margin. This
 * service is not reachable from any tenant endpoint and must not become so.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformCommissionService {

    private final PlatformCommissionEntryRepository repository;
    private final PlatformHotelBookingRepository bookingRepository;
    private final CommissionEntryMapper mapper;
    private final PlatformAuditRecorder auditRecorder;

    /** No FX anywhere in the platform yet, so a mixed-currency sum would be meaningless. */
    private static final String REPORTING_CURRENCY = "INR";

    private static final List<CommissionEntryStatus> OPEN_STATUSES =
            List.of(CommissionEntryStatus.PENDING, CommissionEntryStatus.EARNED);

    // ── Writes ──────────────────────────────────────────────────────────────

    /**
     * Record the earning on a booking the SuperAdmin has just confirmed. One ACCRUAL row, PENDING —
     * the stay has not happened, so the money is booked, not earned (§8 Step 9).
     *
     * <p>Replay-safe: a second call returns the row the first one wrote. The probe runs <b>before</b>
     * the status guard on purpose, so replaying the approval path over a booking that has since been
     * cancelled still returns its accrual instead of refusing.</p>
     *
     * @return the ACCRUAL row for this booking — freshly written, or the one that already existed
     */
    @Transactional
    public PlatformCommissionEntry accrue(PlatformHotelBooking booking) {
        requireBooking(booking);
        String key = accrualKey(booking);

        Optional<PlatformCommissionEntry> existing = repository.findByReferenceKeyAndDeletedAtIsNull(key);
        if (existing.isPresent()) {
            log.debug("Commission already accrued for {} — replay ignored", booking.getBookingCode());
            return existing.get();
        }

        if (!booking.getStatus().isCommitted()) {
            throw new BusinessException(
                    "Booking " + booking.getBookingCode() + " is " + booking.getStatus()
                            + "; commission accrues only once the platform has committed to a supplier.",
                    HttpStatus.CONFLICT);
        }

        BigDecimal earning = scale(booking.getPlatformEarning());
        if (earning.signum() < 0) {
            // Recorded rather than rejected. The approval guard makes this unreachable today, and if a
            // restatement ever produces it, a ledger that refuses to record reality is worse than one
            // that records a loss.
            log.warn("Accruing a NEGATIVE earning of {} for {} — check the approved amounts",
                    earning, booking.getBookingCode());
        }

        PlatformCommissionEntry entry = repository.save(newRow(
                booking,
                CommissionEntryType.ACCRUAL,
                earning,
                CommissionEntryStatus.PENDING,
                effectiveDate(booking.getApprovedAt()),
                key,
                "Accrued on approval of " + booking.getBookingCode()));

        audit(PlatformAuditAction.MARKETPLACE_COMMISSION_ACCRUED, booking, entry,
                "Accrued " + entry.getAmount() + " " + entry.getCurrency()
                        + " on " + booking.getBookingCode()
                        + " (supplier " + entry.getSupplierTotal()
                        + ", tenant payable " + entry.getTenantPayable() + ")");
        return entry;
    }

    /**
     * Take back what a cancellation costs the platform (§9 step 7-8).
     *
     * <p>Writes a NEGATIVE row sized so the ledger nets to exactly {@code retainedEarning} — the
     * amount the snapshotted commercial rule says the platform keeps. The original accrual is never
     * touched; that is the entire point of an append-only ledger, and "the accrual was always 100"
     * has to stay true for every report already produced from it.</p>
     *
     * <p>When the reversal is total (nothing retained) the surviving ACCRUAL rows are flipped to
     * {@code REVERSED} so the status breakdown stops showing money as pending that will never arrive.
     * Already-SETTLED accruals are deliberately left alone: money that has left the building cannot be
     * un-earned by flipping a column, and that case needs a clawback, not a status change.</p>
     *
     * @param retainedEarning what the platform keeps, unsigned. {@code null} means keep nothing.
     * @return the reversal row, or {@link Optional#empty()} if there was nothing to reverse — either
     *         the booking never accrued, or the retained amount already covers the whole net position
     */
    @Transactional
    public Optional<PlatformCommissionEntry> reverse(PlatformHotelBooking booking,
                                                     BigDecimal retainedEarning,
                                                     String reason) {
        requireBooking(booking);
        String key = reversalKey(booking);

        Optional<PlatformCommissionEntry> existing = repository.findByReferenceKeyAndDeletedAtIsNull(key);
        if (existing.isPresent()) {
            log.debug("Commission already reversed for {} — replay ignored", booking.getBookingCode());
            return existing;
        }

        BigDecimal retained = scale(retainedEarning);
        if (retained.signum() < 0) {
            throw new BusinessException("Retained earning cannot be negative: " + retained,
                    HttpStatus.BAD_REQUEST);
        }

        BigDecimal net = netForBooking(booking.getId());
        if (net.signum() <= 0) {
            log.info("Nothing to reverse on {} — net position is {}", booking.getBookingCode(), net);
            return Optional.empty();
        }
        if (retained.compareTo(net) >= 0) {
            log.info("Nothing to reverse on {} — retained {} already covers the net position {}",
                    booking.getBookingCode(), retained, net);
            return Optional.empty();
        }

        BigDecimal amount = retained.subtract(net);   // negative by construction: retained < net

        PlatformCommissionEntry entry = repository.save(newRow(
                booking,
                CommissionEntryType.REVERSAL,
                amount,
                CommissionEntryStatus.REVERSED,
                effectiveDate(booking.getCancelledAt()),
                key,
                reason == null || reason.isBlank()
                        ? "Reversed on cancellation of " + booking.getBookingCode()
                        : reason));

        boolean total = retained.signum() == 0;
        if (total) {
            List<PlatformCommissionEntry> accruals =
                    repository.findAccrualsInStatus(booking.getId(), OPEN_STATUSES);
            accruals.forEach(a -> a.setStatus(CommissionEntryStatus.REVERSED));
            repository.saveAll(accruals);
        }

        audit(PlatformAuditAction.MARKETPLACE_COMMISSION_REVERSED, booking, entry,
                (total ? "Fully reversed " : "Partially reversed ") + amount.abs() + " "
                        + entry.getCurrency() + " on " + booking.getBookingCode()
                        + "; platform retains " + retained + " of " + net);
        return Optional.of(entry);
    }

    /**
     * A manual SuperAdmin correction — a mis-keyed approval, a negotiated write-down, a goodwill
     * credit. Written {@code EARNED}: a human asserting a corrected figure is not queuing it behind a
     * stay that may never happen.
     *
     * @param signedAmount negative writes earning down, positive writes it up. Zero is refused.
     * @param referenceSuffix caller-supplied, so two legitimate corrections on the same booking do not
     *                        collide on the idempotency key while a retried one still lands once
     */
    @Transactional
    public PlatformCommissionEntry adjust(PlatformHotelBooking booking, BigDecimal signedAmount,
                                          String reason, String referenceSuffix) {
        requireBooking(booking);
        if (referenceSuffix == null || referenceSuffix.isBlank()) {
            throw new BusinessException("An adjustment needs a reference so a retry is not applied twice.",
                    HttpStatus.BAD_REQUEST);
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("An adjustment must say why.", HttpStatus.BAD_REQUEST);
        }

        BigDecimal amount = scale(signedAmount);
        if (amount.signum() == 0) {
            throw new BusinessException("A zero adjustment moves no money and would only add noise.",
                    HttpStatus.BAD_REQUEST);
        }

        String key = adjustmentKey(booking, referenceSuffix);
        Optional<PlatformCommissionEntry> existing = repository.findByReferenceKeyAndDeletedAtIsNull(key);
        if (existing.isPresent()) {
            log.debug("Adjustment {} already applied to {}", key, booking.getBookingCode());
            return existing.get();
        }

        PlatformCommissionEntry entry = repository.save(newRow(
                booking,
                CommissionEntryType.ADJUSTMENT,
                amount,
                CommissionEntryStatus.EARNED,
                LocalDate.now(),
                key,
                reason));

        audit(PlatformAuditAction.MARKETPLACE_COMMISSION_ADJUSTED, booking, entry,
                "Adjusted " + amount + " " + entry.getCurrency() + " on " + booking.getBookingCode()
                        + " — " + reason);
        return entry;
    }

    /** Resolve the booking, then {@link #adjust(PlatformHotelBooking, BigDecimal, String, String)}. */
    @Transactional
    public PlatformCommissionEntry adjust(UUID bookingPublicId, BigDecimal signedAmount,
                                          String reason, String referenceSuffix) {
        return adjust(requireBookingByPublicId(bookingPublicId), signedAmount, reason, referenceSuffix);
    }

    /**
     * PENDING → EARNED on one row: the stay happened, or the policy window closed (§8 Step 9).
     *
     * <p>{@code reason} goes to the audit trail only. The row's own {@code reason} explains why it
     * exists and is not rewritten by a later transition — status is the one column that moves.</p>
     *
     * <p>Audited as {@code MARKETPLACE_COMMISSION_ACCRUED} because there is no {@code _EARNED} action;
     * the description carries the {@code PENDING → EARNED} transition explicitly.</p>
     */
    @Transactional
    public PlatformCommissionEntry markEarned(UUID entryPublicId, String reason) {
        return transition(entryPublicId, CommissionEntryStatus.EARNED,
                PlatformAuditAction.MARKETPLACE_COMMISSION_ACCRUED, reason);
    }

    /** Every still-open accrual on one booking, in one call — what a check-in rule actually needs. */
    @Transactional
    public int markEarnedForBooking(Long hotelBookingId, String reason) {
        List<PlatformCommissionEntry> pending = repository.findAccrualsInStatus(
                hotelBookingId, List.of(CommissionEntryStatus.PENDING));
        if (pending.isEmpty()) {
            return 0;
        }
        pending.forEach(e -> e.setStatus(CommissionEntryStatus.EARNED));
        repository.saveAll(pending);

        PlatformCommissionEntry first = pending.get(0);
        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_COMMISSION_ACCRUED, true,
                first.getTenantId(), first.getTenantCode(), "MARKETPLACE_COMMISSION",
                first.getHotelBookingPublicId(),
                "Marked " + pending.size() + " accrual(s) EARNED on " + first.getBookingCode()
                        + (reason == null || reason.isBlank() ? "" : " — " + reason));
        return pending.size();
    }

    /** Paid out, or netted off against the tenant's account. Terminal. */
    @Transactional
    public PlatformCommissionEntry markSettled(UUID entryPublicId, String reason) {
        return transition(entryPublicId, CommissionEntryStatus.SETTLED,
                PlatformAuditAction.MARKETPLACE_COMMISSION_SETTLED, reason);
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** What the platform is left holding on one booking, once reversals and adjustments are applied. */
    @Transactional(readOnly = true)
    public BigDecimal netForBooking(Long hotelBookingId) {
        return scale(repository.sumAmountForBooking(hotelBookingId));
    }

    /** One booking's ledger, oldest first — the order the events happened in. */
    @Transactional(readOnly = true)
    public List<CommissionEntryDto> entriesForBooking(Long hotelBookingId) {
        return repository.findByHotelBookingIdAndDeletedAtIsNullOrderByIdAsc(hotelBookingId)
                .stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Page<CommissionEntryDto> search(Long tenantId, CommissionEntryStatus status,
                                           CommissionEntryType entryType,
                                           LocalDate fromDate, LocalDate toDate, Pageable pageable) {
        return repository.searchForAdmin(tenantId, status, entryType, fromDate, toDate, pageable)
                .map(mapper::toDto);
    }

    /**
     * The platform earning report (§13).
     *
     * <p>Three aggregates over one filter block rather than one query per figure — the breakdowns have
     * to sum to {@link CommissionSummaryDto#getNetEarning()}, and they only do that if they were
     * computed over the same rows.</p>
     */
    @Transactional(readOnly = true)
    public CommissionSummaryDto summary(Long tenantId, LocalDate fromDate, LocalDate toDate) {
        Map<CommissionEntryType, BigDecimal> byType = new EnumMap<>(CommissionEntryType.class);
        long count = 0;
        for (Object[] row : repository.sumGroupedByEntryType(tenantId, fromDate, toDate)) {
            byType.put((CommissionEntryType) row[0], scale(toDecimal(row[1])));
            count += ((Number) row[2]).longValue();
        }

        Map<CommissionEntryStatus, BigDecimal> byStatus = new EnumMap<>(CommissionEntryStatus.class);
        for (Object[] row : repository.sumGroupedByStatus(tenantId, fromDate, toDate)) {
            byStatus.put((CommissionEntryStatus) row[0], scale(toDecimal(row[1])));
        }

        return CommissionSummaryDto.builder()
                .grossAccrued(byType.getOrDefault(CommissionEntryType.ACCRUAL, zero()))
                .totalReversed(byType.getOrDefault(CommissionEntryType.REVERSAL, zero()))
                .totalAdjusted(byType.getOrDefault(CommissionEntryType.ADJUSTMENT, zero()))
                .totalSettlementEntries(byType.getOrDefault(CommissionEntryType.SETTLEMENT, zero()))
                .netEarning(scale(repository.sumNet(tenantId, fromDate, toDate)))
                .pendingBalance(byStatus.getOrDefault(CommissionEntryStatus.PENDING, zero()))
                .earnedBalance(byStatus.getOrDefault(CommissionEntryStatus.EARNED, zero()))
                .reversedBalance(byStatus.getOrDefault(CommissionEntryStatus.REVERSED, zero()))
                .settledBalance(byStatus.getOrDefault(CommissionEntryStatus.SETTLED, zero()))
                .entryCount(count)
                .currency(REPORTING_CURRENCY)
                .tenantId(tenantId)
                .fromDate(fromDate)
                .toDate(toDate)
                .build();
    }

    @Transactional(readOnly = true)
    public CommissionEntryDto toDto(PlatformCommissionEntry entry) {
        return mapper.toDto(entry);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private PlatformCommissionEntry transition(UUID entryPublicId, CommissionEntryStatus target,
                                               PlatformAuditAction action, String reason) {
        PlatformCommissionEntry entry = repository.findByPublicIdAndDeletedAtIsNull(entryPublicId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger entry not found: " + entryPublicId));

        if (entry.getStatus() == target) {
            return entry;   // idempotent
        }
        if (!entry.getStatus().isOpen()) {
            throw new BusinessException(
                    "Ledger entry is " + entry.getStatus() + " and cannot be moved to " + target
                            + ". Correct it with an adjustment row instead.",
                    HttpStatus.CONFLICT);
        }

        CommissionEntryStatus from = entry.getStatus();
        entry.setStatus(target);
        PlatformCommissionEntry saved = repository.save(entry);

        auditRecorder.safeRecord(action, true,
                saved.getTenantId(), saved.getTenantCode(), "MARKETPLACE_COMMISSION", saved.getPublicId(),
                saved.getAmount() + " " + saved.getCurrency() + " on " + saved.getBookingCode()
                        + ": " + from + " → " + target
                        + (reason == null || reason.isBlank() ? "" : " — " + reason));
        return saved;
    }

    /** Snapshot the booking's economics onto the row so a report never has to re-read a restated booking. */
    private PlatformCommissionEntry newRow(PlatformHotelBooking booking, CommissionEntryType type,
                                           BigDecimal amount, CommissionEntryStatus status,
                                           LocalDate effectiveDate, String referenceKey, String reason) {
        return PlatformCommissionEntry.builder()
                .hotelBookingId(booking.getId())
                .hotelBookingPublicId(booking.getPublicId())
                .bookingCode(booking.getBookingCode())
                .tenantId(booking.getTenantId())
                .tenantCode(booking.getTenantCode())
                .entryType(type)
                .amount(amount)
                .currency(booking.getCurrency() == null ? REPORTING_CURRENCY : booking.getCurrency())
                .status(status)
                .supplierTotal(booking.getSupplierTotal())
                .tenantPayable(booking.getTenantPayable())
                .effectiveDate(effectiveDate)
                .reason(reason)
                .referenceKey(referenceKey)
                .build();
    }

    private void audit(PlatformAuditAction action, PlatformHotelBooking booking,
                       PlatformCommissionEntry entry, String description) {
        auditRecorder.safeRecord(action, true,
                booking.getTenantId(), booking.getTenantCode(),
                "MARKETPLACE_COMMISSION", entry.getPublicId(), description);
    }

    /**
     * Locks the booking for the length of the adjustment.
     *
     * <p>{@code findByPublicIdForUpdate} is the only un-scoped finder on the booking repository, and
     * the lock is welcome here rather than merely tolerated: an adjustment snapshots the booking's
     * supplier and payable figures, and doing that against a row a concurrent approval or cancellation
     * is restating would pin the wrong numbers onto a permanent ledger entry.</p>
     */
    private PlatformHotelBooking requireBookingByPublicId(UUID bookingPublicId) {
        return bookingRepository.findByPublicIdForUpdate(bookingPublicId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Marketplace booking not found: " + bookingPublicId));
    }

    private static void requireBooking(PlatformHotelBooking booking) {
        if (booking == null || booking.getPublicId() == null) {
            throw new IllegalArgumentException("A persisted marketplace booking is required.");
        }
    }

    private String accrualKey(PlatformHotelBooking booking) {
        return "ACCRUAL:" + booking.getPublicId();
    }

    private String reversalKey(PlatformHotelBooking booking) {
        return "REVERSAL:" + booking.getPublicId();
    }

    /** 11 + 36 + 1 + 60 = 108, inside the column's 120. The suffix length is validated at the edge. */
    private String adjustmentKey(PlatformHotelBooking booking, String suffix) {
        String key = "ADJUSTMENT:" + booking.getPublicId() + ":" + suffix.trim();
        if (key.length() > 120) {
            throw new BusinessException("Adjustment reference is too long.", HttpStatus.BAD_REQUEST);
        }
        return key;
    }

    /** The accounting date is when the event happened, not when the row finally got written. */
    private static LocalDate effectiveDate(java.time.LocalDateTime at) {
        return at == null ? LocalDate.now() : at.toLocalDate();
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * COALESCE over an empty group can come back as a plain integer rather than a decimal. Routed
     * through {@code toString} rather than {@code doubleValue} — money never touches a double, not even
     * in a defensive branch that is probably unreachable.
     */
    private static BigDecimal toDecimal(Object raw) {
        if (raw == null) return BigDecimal.ZERO;
        if (raw instanceof BigDecimal bd) return bd;
        return new BigDecimal(raw.toString());
    }
}
