package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.booking.api.CrmBookingLinkPort;
import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.common.context.TenantScope;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ApproveMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.CancelMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingAdminDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ReviseMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import com.crm.travelcrm.hotelmarketplace.commission.service.PlatformCommissionService;
import com.crm.travelcrm.hotelmarketplace.notification.MarketplaceNotifier;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The SuperAdmin decision, end to end.
 *
 * <p><b>Deliberately NOT {@code @Transactional}.</b> The CRM projection has to run inside
 * {@code TenantScope.call(...)}, which throws if entered while a transaction is active — it swaps a
 * ThreadLocal, and a half-committed transaction observing the swap is exactly the bug that guard
 * exists to prevent. So the work is two transactions, and the gap between them is closed by
 * <b>compensation, not rollback</b>:</p>
 *
 * <ol>
 *   <li>TX-1 ({@link MarketplacePlatformWriter#confirm}) — platform tables only. Atomic.</li>
 *   <li>TX-2, inside {@code TenantScope} — the tenant's service line and payable.</li>
 *   <li>TX-3 — record how TX-2 went, so {@link MarketplaceCrmSyncScheduler} can finish the job.</li>
 * </ol>
 *
 * <p>The worst case that leaves is "confirmed on the platform, not yet visible in the CRM" — visible,
 * recoverable, and never a wrong confirmation, because the tenant-facing confirmation is read off the
 * platform row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceApprovalOrchestrator
        implements MarketplaceRevisionExpiryScheduler.RevisionExpiryHandler {

    private final MarketplacePlatformWriter platformWriter;
    private final CrmBookingLinkPort crmLink;
    private final MarketplaceBookingMapper mapper;
    private final PlatformAuditRecorder auditRecorder;
    private final PlatformCommissionService commissionService;
    private final MarketplaceNotifier notifier;

    /**
     * How long a revised price stays acceptable when the operator does not say. Short by default —
     * an ON_REQUEST rate is only as good as the phone call it came from.
     */
    @Value("${app.marketplace.revision.default-valid-hours:48}")
    private int defaultRevisionValidHours;

    public MarketplaceBookingAdminDto approve(UUID publicId, ApproveMarketplaceBookingRequest cmd,
                                              Long superAdminId) {
        PlatformHotelBooking row = platformWriter.confirm(publicId, cmd, superAdminId);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_APPROVE, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Confirmed " + row.getHotelNameSnapshot() + " — supplier " + row.getSupplierTotal()
                        + ", tenant payable " + row.getTenantPayable()
                        + ", earning " + row.getPlatformEarning());

        // Accrue BEFORE the CRM projection, and idempotently. The earning is a platform fact
        // established by the approval itself; making it wait on a tenant-side write that is allowed
        // to fail and be replayed would mean a CRM outage quietly costing the platform its revenue
        // record (design §8 Step 9: approved ⇒ PENDING accrual).
        accrueQuietly(row);

        projectToCrm(row);

        // Last, and outside every TenantScope: the listener mutates TenantContext synchronously, so
        // publishing mid-scope destroys the outer tenant value.
        notifier.bookingConfirmed(row);
        return mapper.toAdminDto(row);
    }

    public MarketplaceBookingAdminDto reject(UUID publicId, String reason, Long superAdminId) {
        PlatformHotelBooking row = platformWriter.reject(publicId, reason, superAdminId);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REJECT, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Rejected: " + reason);

        withdrawCrmLine(row, reason);
        notifier.bookingRejected(row);
        return mapper.toAdminDto(row);
    }

    /**
     * Flip the tenant's hotel service line to CANCELLED.
     *
     * <p>Never touches {@code Booking.status}: cancelling a booking mints an immutable cancellation
     * record, a numbered credit note and a frozen P&amp;L — that is the customer cancelling their
     * trip, not a supplier declining a room. An auto-created booking orphaned by a rejection simply
     * stays PENDING with a cancelled hotel line; the tenant decides whether to reuse it, and only
     * they may delete it.</p>
     *
     * <p>A failure here is recorded, not rethrown. The platform decision has already committed and
     * the supplier has already been told — unwinding it because a CRM row would not write is the one
     * outcome worse than a lagging projection.</p>
     */
    /**
     * Write the earning accrual without letting a ledger failure undo a confirmation.
     *
     * <p>The supplier has already been committed to; refusing the approval now would leave a room
     * booked with nobody's record of it. A missing accrual is visible — the verification query in
     * migration PART 15 finds exactly this — and is recoverable by an ADJUSTMENT.</p>
     */
    private void accrueQuietly(PlatformHotelBooking row) {
        try {
            commissionService.accrue(row);
        } catch (RuntimeException e) {
            log.error("Confirmed {} but could not accrue platform earning {}: {}",
                    row.getBookingCode(), row.getPlatformEarning(), e.getMessage(), e);
        }
    }

    private void reverseQuietly(PlatformHotelBooking row, BigDecimal retained, String reason) {
        try {
            commissionService.reverse(row, retained == null ? BigDecimal.ZERO : retained, reason);
        } catch (RuntimeException e) {
            log.error("Cancelled {} but could not reverse its earning accrual: {}",
                    row.getBookingCode(), e.getMessage(), e);
        }
    }

    private void withdrawCrmLine(PlatformHotelBooking row, String reason) {
        try {
            TenantScope.run(row.getTenantId(), () ->
                    crmLink.withdrawHotel(row.getPublicId(), row.getTenantId(), reason));
        } catch (RuntimeException e) {
            log.error("Decided {} but could not withdraw its CRM line: {}",
                    row.getBookingCode(), e.getMessage(), e);
            platformWriter.recordCrmSync(row.getPublicId(), CrmSyncState.FAILED, e.getMessage(), null, null);
        }
    }

    /**
     * Put a revised price to the tenant (design §8 Step 6B).
     *
     * <p>Platform-only: nothing is confirmed, no supplier is committed, and the CRM hotel line stays
     * exactly where it is — PENDING, priced at nothing. There is no tenant-side write to compensate
     * for, which is why this needs neither {@code TenantScope} nor the sync state machine.</p>
     */
    public MarketplaceBookingAdminDto requestRevision(UUID publicId, ReviseMarketplaceBookingRequest cmd,
                                                      Long superAdminId) {
        PlatformHotelBooking row = platformWriter.requestRevision(
                publicId, cmd, superAdminId, defaultRevisionValidHours);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REVISION_REQUESTED, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Revised price put to tenant: " + row.getRevisionPreviousPayable() + " → "
                        + row.getRevisedTenantPayable() + " (" + cmd.getReason() + "), valid until "
                        + row.getRevisionExpiresAt());

        notifier.revisionRequired(row);
        return mapper.toAdminDto(row);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implemented here rather than on the writer so the sweep gets the audit trail and the tenant
     * notification, not just the status flip.</p>
     */
    @Override
    public void expire(UUID bookingPublicId) {
        expireRevision(bookingPublicId);
    }

    /**
     * A revised price nobody answered. Driven by {@link MarketplaceRevisionExpiryScheduler}.
     *
     * <p>Returns the request to the SuperAdmin queue rather than killing it — see the writer for why
     * — but the part that matters is that the stale offer stops being acceptable.</p>
     */
    public void expireRevision(UUID publicId) {
        PlatformHotelBooking row = platformWriter.expireRevision(publicId);
        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REVISION_EXPIRED, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Revised price expired unanswered; returned to review.");
        notifier.revisionExpired(row);
    }

    /**
     * Settle a cancellation: record what the supplier kept, unwind the tenant's line.
     *
     * <p>Same two-transaction shape as {@link #approve}, and for the same reason — the CRM write
     * needs {@code TenantScope}, which refuses an active transaction. A failure to withdraw the CRM
     * line is logged and recorded, never rolled back: the supplier has already been told.</p>
     *
     * <p>The CRM <b>Booking</b> is untouched. {@code BookingServiceImpl.cancel()} mints an immutable
     * cancellation, a numbered credit note and a frozen P&amp;L — that is the customer calling off
     * their trip, not one hotel line falling out of it.</p>
     */
    public MarketplaceBookingAdminDto cancel(UUID publicId, CancelMarketplaceBookingRequest cmd,
                                             Long superAdminId) {
        PlatformHotelBooking row = platformWriter.settleCancellation(publicId, cmd, superAdminId);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_CANCEL, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Cancelled " + row.getHotelNameSnapshot() + " — charge " + row.getCancellationCharge()
                        + ", refund " + row.getTenantRefundAmount() + " (" + cmd.getReason() + ")");

        // Reverse down to what the platform actually keeps. A REVERSAL is a new signed row, never an
        // edit of the accrual: recomputing history is exactly what an append-only ledger exists to
        // prevent (design §9 clause 8).
        reverseQuietly(row, cmd.getRetainedPlatformEarning(), cmd.getReason());

        withdrawCrmLine(row, "Cancelled: " + cmd.getReason());
        notifier.bookingCancelled(row);
        return mapper.toAdminDto(row);
    }

    public MarketplaceBookingAdminDto takeUnderReview(UUID publicId) {
        PlatformHotelBooking row = platformWriter.takeUnderReview(publicId);
        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REVIEW, true,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Under review");
        return mapper.toAdminDto(row);
    }

    /**
     * TX-2 + TX-3. Public so the compensating scheduler replays exactly this path rather than a
     * near-copy that can drift from it.
     *
     * <p>Idempotent: {@code projectHotel} upserts on the marketplace publicId, so a replay after a
     * partial success updates the same rows instead of adding more.</p>
     */
    /**
     * Give up on a row whose projection keeps failing the same way. Called by the scheduler once the
     * attempt cap is reached — a distinct terminal state, so the row stops consuming ticks and starts
     * being visible as something a human has to resolve.
     */
    public void projectToCrmAbandon(PlatformHotelBooking row) {
        platformWriter.recordCrmSync(row.getPublicId(), CrmSyncState.ABANDONED,
                "Retries exhausted. Last error: " + row.getCrmSyncError(), null, null);
        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_CRM_SYNC_FAILED, false,
                row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "CRM projection abandoned after repeated failures: " + row.getCrmSyncError());
        // Tell the tenant too. An abandoned projection means their booking is confirmed with the
        // hotel but the payable is missing from their own books — silence here is how that becomes
        // a reconciliation surprise months later.
        notifier.crmSyncFailed(row);
    }

    public void projectToCrm(PlatformHotelBooking row) {
        if (row.getCrmBookingPublicId() == null) {
            platformWriter.recordCrmSync(row.getPublicId(), CrmSyncState.ABANDONED,
                    "No CRM booking is linked to this request.", null, null);
            return;
        }
        try {
            CrmBookingLinkPort.CrmMarketplaceProjection projection = TenantScope.call(
                    row.getTenantId(),
                    () -> crmLink.projectHotel(new CrmBookingLinkPort.MarketplaceHotelProjectionCommand(
                            row.getTenantId(),
                            row.getCrmBookingPublicId(),
                            row.getPublicId(),
                            row.getHotelNameSnapshot(),
                            row.getHotelNameSnapshot(),
                            row.getCityNameSnapshot(),
                            row.getRoomNameSnapshot(),
                            row.getMealPlanSnapshot(),
                            row.getCheckIn(),
                            row.getCheckOut(),
                            row.getRooms(),
                            ServiceItemStatus.CONFIRMED,
                            row.getSupplierConfirmationNumber(),
                            row.getTenantPayable(),
                            row.getCheckIn(),          // payable falls due at check-in
                            null)));

            platformWriter.recordCrmSync(row.getPublicId(), CrmSyncState.SYNCED, null,
                    projection.serviceItemPublicId(), projection.expensePublicId());

        } catch (RuntimeException e) {
            // Confirmed on the platform, not yet in the CRM. Recorded and retried, never rolled back:
            // the supplier has already been committed to.
            log.error("CRM projection failed for {} (tenant {}): {}",
                    row.getBookingCode(), row.getTenantId(), e.getMessage(), e);
            platformWriter.recordCrmSync(row.getPublicId(), CrmSyncState.FAILED, e.getMessage(), null, null);
            auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_CRM_SYNC_FAILED, false,
                    row.getTenantId(), row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                    "CRM projection failed: " + e.getMessage());
        }
    }
}
