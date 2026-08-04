package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.booking.api.CrmBookingLinkPort;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.dto.MarketplaceBookingTenantDto;
import com.crm.travelcrm.hotelmarketplace.booking.dto.SubmitMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.mapper.MarketplaceBookingMapper;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.notification.MarketplaceNotifier;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The tenant's side of a marketplace booking: submit a request, and read your own.
 *
 * <p><b>This class is deliberately NOT {@code @Transactional}.</b> The write it orchestrates is one
 * transaction inside {@link MarketplaceBookingWriter}; keeping the orchestration outside leaves room
 * for the supplier round-trip a later release will add here (a network call inside a transaction is
 * how you hold a row lock for an unbounded time), and lets the idempotency retry re-read after the
 * losing transaction has rolled back.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceBookingRequestService {

    private final PlatformHotelBookingRepository repository;
    private final MarketplaceBookingWriter writer;
    private final MarketplacePlatformWriter platformWriter;
    private final MarketplaceBookingMapper mapper;
    private final CrmBookingLinkPort crmLink;
    private final CurrentUserProvider currentUserProvider;
    private final PlatformAuditRecorder auditRecorder;
    private final MarketplaceNotifier notifier;

    /**
     * Submit a booking request.
     *
     * <p>Idempotent on {@code idempotencyKey}: a repeat returns the ORIGINAL request. That guard is
     * load-bearing rather than defensive, because {@code BookingServiceImpl.create()} has no
     * idempotency of its own — without it a double-click mints two CRM bookings, two booking codes
     * and two quota slots.</p>
     */
    public MarketplaceBookingTenantDto submit(SubmitMarketplaceBookingRequest req) {
        Long tenantId = requireTenantId();

        // Fast path: already submitted.
        var existing = repository.findByTenantIdAndIdempotencyKeyAndDeletedAtIsNull(
                tenantId, req.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay of marketplace request {} for tenant {}",
                    existing.get().getBookingCode(), tenantId);
            return toDto(existing.get());
        }

        PlatformHotelBooking saved;
        try {
            saved = writer.persistRequest(req, tenantId, currentUserProvider.currentUserIdOrNull());
        } catch (DataIntegrityViolationException race) {
            // Two submits with the same key raced. Postgres blocked the loser on the unique index
            // until the winner committed, so by the time we land here the winner is readable — and
            // the loser's whole transaction, CRM booking included, rolled back with it. That is the
            // correct outcome, not a partial one.
            return repository.findByTenantIdAndIdempotencyKeyAndDeletedAtIsNull(tenantId, req.getIdempotencyKey())
                    .map(this::toDto)
                    .orElseThrow(() -> race);
        }

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REQUEST, true,
                tenantId, saved.getTenantCode(), "MARKETPLACE_BOOKING", saved.getPublicId(),
                "Tenant requested " + saved.getHotelNameSnapshot() + " " + saved.getCheckIn()
                        + " → " + saved.getCheckOut());

        // The platform realm, not the tenant's: this is work landing in the SuperAdmin queue, and
        // the tenant already knows — they just pressed the button.
        notifier.superAdminRequestSubmitted(saved);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<MarketplaceBookingTenantDto> listMine(Pageable pageable, MarketplaceBookingStatus status) {
        Long tenantId = requireTenantId();
        return repository.searchForTenant(tenantId, status, pageable).map(this::toDto);
    }

    // ── Answering a revised price (design §8 Step 6B) ───────────────────────

    /**
     * Accept the revised amount. The request goes back to the SuperAdmin for final approval — this
     * does not confirm anything, and cannot: {@code TENANT_ACCEPTED} is approvable, not confirmed.
     */
    public MarketplaceBookingTenantDto acceptRevision(UUID publicId) {
        Long tenantId = requireTenantId();
        PlatformHotelBooking row = platformWriter.acceptRevision(publicId, tenantId);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REVISION_ACCEPTED, true,
                tenantId, row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Tenant accepted the revised payable " + row.getTenantPayable());

        return toDto(row);
    }

    /**
     * Decline it. The request ends, and the CRM hotel line is withdrawn so the booking does not keep
     * a pending hotel nobody is working on.
     *
     * <p>Runs on the tenant's own request thread, so {@code TenantContext} is already correct and the
     * CRM write needs no {@code TenantScope} — unlike the SuperAdmin path, which has no tenant at all.</p>
     */
    public MarketplaceBookingTenantDto declineRevision(UUID publicId, String reason) {
        Long tenantId = requireTenantId();
        PlatformHotelBooking row = platformWriter.declineRevision(publicId, tenantId, reason);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_REVISION_DECLINED, true,
                tenantId, row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Tenant declined the revised payable " + row.getRevisedTenantPayable()
                        + (reason == null ? "" : " — " + reason));

        withdrawCrmLineQuietly(row, row.getRejectionReason());
        return toDto(row);
    }

    // ── Cancellation (design §9) ────────────────────────────────────────────

    /**
     * Cancel, or ask to.
     *
     * <p>Which of the two it is depends entirely on whether a room is being held, so the tenant does
     * not get to choose and does not need to: a confirmed booking becomes a
     * {@code CANCEL_REQUESTED} the platform works with the supplier, and anything earlier is simply
     * withdrawn, free, immediately. One endpoint, because from the tenant's side it is one intention.</p>
     */
    public MarketplaceBookingTenantDto cancel(UUID publicId, String reason) {
        Long tenantId = requireTenantId();

        PlatformHotelBooking current = repository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking request not found: " + publicId));

        if (current.getStatus().isCancelRequestable()) {
            PlatformHotelBooking row = platformWriter.requestCancellation(publicId, tenantId, reason);
            auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_CANCEL_REQUESTED, true,
                    tenantId, row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                    "Tenant asked to cancel " + row.getHotelNameSnapshot()
                            + (reason == null ? "" : " — " + reason));
            // The CRM line stays CONFIRMED here on purpose: the room is still held and the payable
            // still stands until the platform settles the charge with the hotel. Withdrawing it now
            // would tell the tenant's own books the cost had gone away while it had not.
            notifier.superAdminCancelRequested(row);
            return toDto(row);
        }

        PlatformHotelBooking row = platformWriter.withdraw(publicId, tenantId, reason);
        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_BOOKING_CANCEL, true,
                tenantId, row.getTenantCode(), "MARKETPLACE_BOOKING", row.getPublicId(),
                "Tenant withdrew the request" + (reason == null ? "" : " — " + reason));

        withdrawCrmLineQuietly(row, row.getCancellationReason());
        return toDto(row);
    }

    /**
     * One of the tenant's own requests.
     *
     * <p>Ownership is inside the finder, not bolted on afterwards. These rows extend
     * {@code BaseEntity}, so no Hibernate filter scopes them and the ArchUnit isolation test does not
     * cover this repository — a bare by-publicId read here would hand over another tenant's guest
     * details and negotiated payable with nothing in the build to catch it.</p>
     */
    @Transactional(readOnly = true)
    public MarketplaceBookingTenantDto getMine(UUID publicId) {
        Long tenantId = requireTenantId();
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Booking request not found: " + publicId));
    }

    // ── internals ───────────────────────────────────────────────────────────

    /**
     * Withdraw the CRM hotel line after the tenant themselves ended the request.
     *
     * <p>Best-effort. The platform row is already terminal and is what every screen reads; a service
     * line that failed to flip is a stale row somebody can see and fix, whereas throwing here would
     * report the whole cancellation as failed when it demonstrably succeeded.</p>
     */
    private void withdrawCrmLineQuietly(PlatformHotelBooking row, String reason) {
        try {
            crmLink.withdrawHotel(row.getPublicId(), row.getTenantId(), reason);
        } catch (RuntimeException e) {
            log.error("Ended marketplace request {} but could not withdraw its CRM line: {}",
                    row.getBookingCode(), e.getMessage(), e);
        }
    }

    private MarketplaceBookingTenantDto toDto(PlatformHotelBooking b) {
        // Through the port, never the CRM repository or entity: this module must stay removable, and
        // reaching into booking's persistence layer for one string is exactly the coupling that would
        // stop it being so.
        String crmCode = b.getCrmBookingPublicId() == null ? null
                : crmLink.lookup(b.getCrmBookingPublicId(), b.getTenantId())
                        .map(CrmBookingLinkPort.CrmBookingRef::bookingCode)
                        .orElse(null);
        return mapper.toTenantDto(b, crmCode);
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty — the marketplace request API is tenant-facing.");
        }
        return tenantId;
    }
}
