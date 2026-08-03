package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ApproveMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.CancelMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ReviseMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The PLATFORM half of a decision — one short transaction over platform tables only, with no tenant
 * context and no CRM write.
 *
 * <p>Separate bean from {@link MarketplaceApprovalOrchestrator} on purpose: the orchestrator must
 * NOT be transactional (it enters {@code TenantScope}, which refuses an active transaction), so the
 * transactional half has to live behind a real Spring proxy. A private method on the orchestrator
 * would be self-invoked and silently non-transactional.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplacePlatformWriter {

    private final PlatformHotelBookingRepository repository;

    /**
     * Move to CONFIRMED and stamp the agreed money. Atomic.
     *
     * <p>The status precondition IS the approve-retry idempotency: approval is a guarded transition,
     * not a blind write, so a second approve on an already-CONFIRMED row returns it unchanged rather
     * than re-running the side effects. The pessimistic lock is what makes the guard meaningful —
     * without it two SuperAdmins both read REQUESTED, both pass, and one loses only at flush, after
     * its effects have run.</p>
     */
    @Transactional
    public PlatformHotelBooking confirm(UUID publicId, ApproveMarketplaceBookingRequest cmd, Long superAdminId) {
        PlatformHotelBooking row = lock(publicId);

        if (row.getStatus() == MarketplaceBookingStatus.CONFIRMED) {
            return row;   // idempotent replay
        }
        if (!row.getStatus().isApprovable()) {
            throw new BusinessException(
                    "This request is " + row.getStatus() + " and can no longer be approved.",
                    HttpStatus.CONFLICT);
        }

        BigDecimal supplier = scale(cmd.getSupplierTotal());
        BigDecimal payable = scale(cmd.getTenantPayable());
        if (payable.compareTo(supplier) < 0) {
            throw new BusinessException(
                    "Tenant payable (" + payable + ") is below the supplier total (" + supplier
                            + "). Confirm the amounts before approving.", HttpStatus.BAD_REQUEST);
        }

        // A tenant who accepted a revision accepted an AMOUNT, not a licence to be charged whatever
        // comes next. Approving at a different figure here would make the whole revision round-trip
        // decorative — the platform could put 4,400 to the tenant, take the yes, and confirm at
        // 5,000. The guard is a 409 rather than a silent clamp because the operator has to know
        // their input was rejected, and the remedy is a fresh revision the tenant sees.
        if (row.getStatus() == MarketplaceBookingStatus.TENANT_ACCEPTED
                && row.getTenantPayable() != null
                && payable.compareTo(scale(row.getTenantPayable())) != 0) {
            throw new BusinessException(
                    "This tenant accepted " + scale(row.getTenantPayable()) + ", not " + payable
                            + ". Raise a new revision if the price has moved again.",
                    HttpStatus.CONFLICT);
        }

        row.setStatus(MarketplaceBookingStatus.CONFIRMED);
        row.setSupplierTotal(supplier);
        row.setTenantPayable(payable);
        row.setPlatformEarning(payable.subtract(supplier));
        row.setSupplierConfirmationNumber(cmd.getSupplierConfirmationNumber());
        row.setCancellationTermsSnapshot(cmd.getCancellationTermsSnapshot());
        row.setInternalNotes(cmd.getInternalNotes());
        row.setApprovedBySuperAdminId(superAdminId);
        row.setApprovedAt(LocalDateTime.now());
        // The CRM projection happens AFTER this transaction commits, in its own tenant-scoped one.
        row.setCrmSyncState(CrmSyncState.PENDING);
        row.setCrmSyncError(null);

        return repository.save(row);
    }

    @Transactional
    public PlatformHotelBooking reject(UUID publicId, String reason, Long superAdminId) {
        PlatformHotelBooking row = lock(publicId);
        if (row.getStatus() == MarketplaceBookingStatus.REJECTED) {
            return row;
        }
        if (row.getStatus().isTerminal() || row.getStatus() == MarketplaceBookingStatus.CONFIRMED) {
            throw new BusinessException(
                    "This request is " + row.getStatus() + " and can no longer be rejected.",
                    HttpStatus.CONFLICT);
        }
        row.setStatus(MarketplaceBookingStatus.REJECTED);
        row.setRejectionReason(reason);
        row.setApprovedBySuperAdminId(superAdminId);
        return repository.save(row);
    }

    /** REQUESTED → UNDER_REVIEW, so the queue shows who is working what. */
    @Transactional
    public PlatformHotelBooking takeUnderReview(UUID publicId) {
        PlatformHotelBooking row = lock(publicId);
        if (row.getStatus() == MarketplaceBookingStatus.REQUESTED) {
            row.setStatus(MarketplaceBookingStatus.UNDER_REVIEW);
            return repository.save(row);
        }
        return row;
    }

    /** Record how the CRM projection went. Tiny transaction, deliberately separate from the CRM write. */
    @Transactional
    public void recordCrmSync(UUID publicId, CrmSyncState state, String error,
                              UUID serviceItemPublicId, UUID expensePublicId) {
        repository.findByPublicIdForUpdate(publicId).ifPresent(row -> {
            row.setCrmSyncState(state);
            row.setCrmSyncError(error);
            if (state == CrmSyncState.FAILED || state == CrmSyncState.ABANDONED) {
                row.setCrmSyncAttempts(row.getCrmSyncAttempts() == null ? 1 : row.getCrmSyncAttempts() + 1);
            }
            if (serviceItemPublicId != null) row.setCrmServiceItemPublicId(serviceItemPublicId);
            if (expensePublicId != null) row.setCrmExpensePublicId(expensePublicId);
            repository.save(row);
        });
    }

    // ── Price revision (design §8 Step 6B) ──────────────────────────────────

    /**
     * Put a revised price to the tenant. Availability exists; the money moved.
     *
     * <p>The proposal is written to the {@code revised*} fields and NOT onto {@link
     * PlatformHotelBooking#getTenantPayable()}. Writing it live would make "what you owe" mean "what
     * you might owe" for as long as the tenant takes to answer, and a tenant who never answers would
     * have been repriced without ever agreeing to anything.</p>
     */
    @Transactional
    public PlatformHotelBooking requestRevision(UUID publicId, ReviseMarketplaceBookingRequest cmd,
                                                Long superAdminId, int defaultValidHours) {
        PlatformHotelBooking row = lock(publicId);

        if (!row.getStatus().isRevisable()) {
            throw new BusinessException(
                    "This request is " + row.getStatus() + " and can no longer be repriced.",
                    HttpStatus.CONFLICT);
        }

        BigDecimal supplier = scale(cmd.getRevisedSupplierTotal());
        BigDecimal payable = scale(cmd.getRevisedTenantPayable());
        if (payable.compareTo(supplier) < 0) {
            throw new BusinessException(
                    "Revised tenant payable (" + payable + ") is below the revised supplier total ("
                            + supplier + ").", HttpStatus.BAD_REQUEST);
        }

        int validHours = cmd.getValidForHours() == null ? defaultValidHours : cmd.getValidForHours();

        row.setRevisionPreviousPayable(row.getTenantPayable());
        row.setRevisedSupplierTotal(supplier);
        row.setRevisedTenantPayable(payable);
        row.setRevisedCancellationTerms(cmd.getRevisedCancellationTerms());
        row.setPriceRevisionReason(cmd.getReason());
        row.setRevisionRequestedAt(LocalDateTime.now());
        row.setRevisionExpiresAt(LocalDateTime.now().plusHours(validHours));
        row.setRevisionRespondedAt(null);
        row.setRevisionCount(row.getRevisionCount() == null ? 1 : row.getRevisionCount() + 1);
        if (cmd.getInternalNotes() != null) {
            row.setInternalNotes(cmd.getInternalNotes());
        }
        row.setApprovedBySuperAdminId(superAdminId);
        row.setStatus(MarketplaceBookingStatus.TENANT_APPROVAL_REQUIRED);

        return repository.save(row);
    }

    /**
     * The tenant accepts. The proposal is <b>promoted</b> onto the live money fields, which is what
     * makes {@code confirm} able to check that the approved figure is the one that was agreed.
     */
    @Transactional
    public PlatformHotelBooking acceptRevision(UUID publicId, Long tenantId) {
        PlatformHotelBooking row = lockOwned(publicId, tenantId);

        if (row.getStatus() == MarketplaceBookingStatus.TENANT_ACCEPTED) {
            return row;   // idempotent: a double-click is not a second acceptance
        }
        if (!row.getStatus().awaitsTenant()) {
            throw new BusinessException(
                    "There is no open price revision on this request.", HttpStatus.CONFLICT);
        }
        if (isRevisionExpired(row)) {
            throw new BusinessException(
                    "This revised price expired on " + row.getRevisionExpiresAt()
                            + ". Ask the platform to re-check availability.", HttpStatus.CONFLICT);
        }

        row.setSupplierTotal(scale(row.getRevisedSupplierTotal()));
        row.setTenantPayable(scale(row.getRevisedTenantPayable()));
        row.setPlatformEarning(scale(row.getRevisedTenantPayable()).subtract(scale(row.getRevisedSupplierTotal())));
        if (row.getRevisedCancellationTerms() != null) {
            row.setCancellationTermsSnapshot(row.getRevisedCancellationTerms());
        }
        // The offer has been consumed, so clear it. What was accepted is now simply the payable, and
        // the history survives in revisionPreviousPayable + priceRevisionReason + revisionCount.
        // Leaving a non-null revisedTenantPayable behind would make "there is an open offer" and
        // "there was one, and it was taken" indistinguishable to any caller reading the field
        // instead of the status.
        row.setRevisedSupplierTotal(null);
        row.setRevisedTenantPayable(null);
        row.setRevisedCancellationTerms(null);
        row.setRevisionExpiresAt(null);
        row.setRevisionRespondedAt(LocalDateTime.now());
        row.setStatus(MarketplaceBookingStatus.TENANT_ACCEPTED);

        return repository.save(row);
    }

    /**
     * The tenant refuses the new price.
     *
     * <p>Terminates the request as {@code REJECTED} — the enum's own contract, which names a declined
     * revision as one of the three things that state covers. Nothing was confirmed, so there is no
     * supplier to unwind and no charge to settle.</p>
     */
    @Transactional
    public PlatformHotelBooking declineRevision(UUID publicId, Long tenantId, String reason) {
        PlatformHotelBooking row = lockOwned(publicId, tenantId);

        if (row.getStatus() == MarketplaceBookingStatus.REJECTED) {
            return row;
        }
        if (!row.getStatus().awaitsTenant()) {
            throw new BusinessException(
                    "There is no open price revision on this request.", HttpStatus.CONFLICT);
        }

        row.setRevisionRespondedAt(LocalDateTime.now());
        row.setRejectionReason("Tenant declined the revised price"
                + (reason == null || reason.isBlank() ? "." : ": " + reason.trim()));
        row.setStatus(MarketplaceBookingStatus.REJECTED);

        return repository.save(row);
    }

    /**
     * The tenant never answered inside the validity window.
     *
     * <p>Returns the request to {@code UNDER_REVIEW} and clears the offer, rather than killing it.
     * Two reasons: a real order must not die because somebody was on leave for a day, and — the
     * load-bearing one — a stale price left readable is a price that can still be accepted, at which
     * point the platform is committed to a rate the supplier withdrew. Clearing the fields is the
     * part that matters; the state merely puts it back in front of an operator.</p>
     */
    @Transactional
    public PlatformHotelBooking expireRevision(UUID publicId) {
        PlatformHotelBooking row = lock(publicId);

        if (!row.getStatus().awaitsTenant()) {
            return row;   // somebody got there first
        }

        row.setRevisedSupplierTotal(null);
        row.setRevisedTenantPayable(null);
        row.setRevisedCancellationTerms(null);
        row.setRevisionExpiresAt(null);
        row.setRevisionRespondedAt(LocalDateTime.now());
        row.setPriceRevisionReason(
                "The revised price offered on " + row.getRevisionRequestedAt() + " expired unanswered.");
        row.setStatus(MarketplaceBookingStatus.UNDER_REVIEW);

        return repository.save(row);
    }

    // ── Cancellation (design §9) ────────────────────────────────────────────

    /**
     * The tenant asks to cancel a CONFIRMED booking.
     *
     * <p>A request, not a cancellation: a room is held with the supplier and what it costs to give
     * back depends on the snapshotted policy and on what the hotel allows on the day. The tenant
     * cannot know either, so they cannot be the one to decide it.</p>
     */
    @Transactional
    public PlatformHotelBooking requestCancellation(UUID publicId, Long tenantId, String reason) {
        PlatformHotelBooking row = lockOwned(publicId, tenantId);

        if (row.getStatus() == MarketplaceBookingStatus.CANCEL_REQUESTED) {
            return row;
        }
        if (!row.getStatus().isCancelRequestable()) {
            throw new BusinessException(
                    "This booking is " + row.getStatus() + " and cannot be cancelled.",
                    HttpStatus.CONFLICT);
        }

        row.setCancelRequestedAt(LocalDateTime.now());
        row.setCancelRequestReason(reason);
        row.setStatus(MarketplaceBookingStatus.CANCEL_REQUESTED);

        return repository.save(row);
    }

    /**
     * The tenant withdraws a request nothing has been committed against yet.
     *
     * <p>Straight to {@code CANCELLED} with no charge and no supplier conversation — there is no
     * room being held, so there is nothing to negotiate. Distinct from {@code REJECTED}, which
     * records that the <i>platform</i> refused.</p>
     */
    @Transactional
    public PlatformHotelBooking withdraw(UUID publicId, Long tenantId, String reason) {
        PlatformHotelBooking row = lockOwned(publicId, tenantId);

        if (row.getStatus() == MarketplaceBookingStatus.CANCELLED) {
            return row;
        }
        if (!row.getStatus().isTenantWithdrawable()) {
            throw new BusinessException(
                    "This request is " + row.getStatus() + " and can no longer be withdrawn"
                            + (row.getStatus() == MarketplaceBookingStatus.CONFIRMED
                                    ? " — it is confirmed, so request a cancellation instead." : "."),
                    HttpStatus.CONFLICT);
        }

        row.setCancelledAt(LocalDateTime.now());
        row.setCancellationReason(reason == null || reason.isBlank()
                ? "Withdrawn by the tenant." : "Withdrawn by the tenant: " + reason.trim());
        row.setCancellationCharge(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        row.setTenantRefundAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        row.setStatus(MarketplaceBookingStatus.CANCELLED);

        return repository.save(row);
    }

    /**
     * A SuperAdmin settles the cancellation: what the supplier kept, and what goes back.
     *
     * <p>The refund is <b>derived, never accepted from the client</b>, and the two validations are
     * the ones that stop the money going somewhere it shouldn't: a charge above the payable would
     * mint a negative refund, and earning retained beyond the charge collected would be the platform
     * paying its commission out of the tenant's refund.</p>
     */
    @Transactional
    public PlatformHotelBooking settleCancellation(UUID publicId, CancelMarketplaceBookingRequest cmd,
                                                   Long superAdminId) {
        PlatformHotelBooking row = lock(publicId);

        if (row.getStatus() == MarketplaceBookingStatus.CANCELLED) {
            return row;   // idempotent replay
        }
        if (!row.getStatus().isAdminCancellable()) {
            throw new BusinessException(
                    "This booking is " + row.getStatus() + " and cannot be cancelled.",
                    HttpStatus.CONFLICT);
        }

        BigDecimal payable = scale(row.getTenantPayable());
        BigDecimal charge = scale(cmd.getCancellationCharge());
        if (charge.compareTo(payable) > 0) {
            throw new BusinessException(
                    "The cancellation charge (" + charge + ") exceeds what the tenant owes ("
                            + payable + ").", HttpStatus.BAD_REQUEST);
        }

        BigDecimal retained = scale(cmd.getRetainedPlatformEarning());
        if (retained.compareTo(charge) > 0) {
            throw new BusinessException(
                    "Retained platform earning (" + retained + ") exceeds the cancellation charge ("
                            + charge + "). The platform cannot keep commission out of the tenant's refund.",
                    HttpStatus.BAD_REQUEST);
        }

        row.setCancellationCharge(charge);
        row.setTenantRefundAmount(payable.subtract(charge));
        row.setCancellationReason(cmd.getReason());
        row.setCancelledAt(LocalDateTime.now());
        row.setCancelledBySuperAdminId(superAdminId);
        if (cmd.getInternalNotes() != null) {
            row.setInternalNotes(cmd.getInternalNotes());
        }
        row.setStatus(MarketplaceBookingStatus.CANCELLED);

        return repository.save(row);
    }

    /**
     * The trip this booking rides on was cancelled in the CRM.
     *
     * <p>The inverse signal. Without it the platform row stays {@code CONFIRMED} — room still held
     * with the supplier, accrual still standing — while the tenant's own records say the trip is
     * off. It lands as {@code CANCEL_REQUESTED} rather than {@code CANCELLED} on purpose: a supplier
     * still has to be told, and the charge is still an operator's to establish.</p>
     *
     * <p><b>{@code REQUIRES_NEW}, and it is load-bearing.</b> The only caller runs inside an
     * {@code afterCommit} callback of the CRM cancellation. There the transaction has already
     * committed but its resources are still bound, so {@code REQUIRED} would join a transaction that
     * will never commit again and this write would be discarded in silence — the same defect that
     * was dropping in-app notification rows. A fresh transaction is the only way the row lands.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PlatformHotelBooking> onCrmBookingCancelled(UUID publicId, Long tenantId, String reason) {
        Optional<PlatformHotelBooking> found = repository.findByPublicIdForUpdate(publicId)
                .filter(r -> r.getTenantId().equals(tenantId));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PlatformHotelBooking row = found.get();

        if (row.getStatus().isTerminal() || row.getStatus() == MarketplaceBookingStatus.CANCEL_REQUESTED) {
            return Optional.empty();   // already unwound, or never confirmed — nothing to raise
        }

        if (row.getStatus() == MarketplaceBookingStatus.CONFIRMED) {
            row.setCancelRequestedAt(LocalDateTime.now());
            row.setCancelRequestReason("The CRM booking was cancelled: " + reason);
            row.setStatus(MarketplaceBookingStatus.CANCEL_REQUESTED);
        } else {
            // Nothing was committed to a supplier, so this can be closed outright.
            row.setCancelledAt(LocalDateTime.now());
            row.setCancellationReason("The CRM booking was cancelled: " + reason);
            row.setCancellationCharge(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            row.setTenantRefundAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            row.setStatus(MarketplaceBookingStatus.CANCELLED);
        }
        return Optional.of(repository.save(row));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private PlatformHotelBooking lock(UUID publicId) {
        return repository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking request not found: " + publicId));
    }

    /**
     * Lock, then prove ownership — and 404 rather than 403 on a mismatch, so a foreign publicId is
     * indistinguishable from one that does not exist.
     *
     * <p>The lock has to come first because these rows carry no Hibernate tenant filter, so there is
     * no scoped locking finder to reach for; the ownership check is the caller's job and this is the
     * one place it happens for every tenant-initiated transition.</p>
     */
    private PlatformHotelBooking lockOwned(UUID publicId, Long tenantId) {
        PlatformHotelBooking row = lock(publicId);
        if (tenantId == null || !tenantId.equals(row.getTenantId())) {
            throw new ResourceNotFoundException("Booking request not found: " + publicId);
        }
        return row;
    }

    private static boolean isRevisionExpired(PlatformHotelBooking row) {
        return row.getRevisionExpiresAt() != null
                && row.getRevisionExpiresAt().isBefore(LocalDateTime.now());
    }

    private static BigDecimal scale(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
