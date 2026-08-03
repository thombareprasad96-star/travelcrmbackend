package com.crm.travelcrm.hotelmarketplace.voucher.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.VoucherStatus;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.UUID;

/**
 * Issue, revoke and render the guest-facing hotel voucher (design §7, §8 Step 7).
 *
 * <p><b>Voucher state is a second axis, not a booking state.</b> Nothing here can change
 * {@code status}: a document that failed to render, or one an operator withdrew to reissue with a
 * corrected guest name, must never make the room the hotel is already holding appear to un-confirm
 * itself.</p>
 *
 * <p><b>Issuing accrues nothing.</b> Design §8 Step 9 is explicit — commission is accrued at
 * approval; "the voucher action must never create commission a second time". This class therefore
 * touches no ledger at all.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceVoucherService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MINT_ATTEMPTS = 5;

    private final PlatformHotelBookingRepository repository;
    private final MarketplaceVoucherPdfRenderer renderer;
    private final PlatformAuditRecorder auditRecorder;
    private final EntityManager entityManager;

    /**
     * Produce the voucher for a committed booking.
     *
     * <p>Idempotent on {@link VoucherStatus#ISSUED} — re-issuing an already-issued voucher returns the
     * row untouched rather than re-stamping {@code voucherIssuedAt}, because the issue date is printed
     * on a document a guest may already be carrying.</p>
     *
     * <p>Re-issuing from {@link VoucherStatus#REVOKED} IS allowed and is the correction path: the
     * number is kept (see {@link #revoke}) and {@code voucherRevokedAt} is cleared, so a non-null
     * revoked-at always means "withdrawn right now" rather than "was withdrawn once". The audit log
     * is where the withdrawal history lives.</p>
     */
    @Transactional
    public PlatformHotelBooking issue(UUID bookingPublicId, Long superAdminId) {
        PlatformHotelBooking row = lock(bookingPublicId);

        if (row.getVoucherStatus() == VoucherStatus.ISSUED) {
            return row;
        }
        if (!row.getStatus().isCommitted()) {
            // A voucher is a promise to a guest that a room exists. Until the platform has committed
            // to the supplier there is nothing to promise, and a PDF handed over early is one a hotel
            // desk will refuse.
            throw new BusinessException(
                    "This booking is " + row.getStatus() + ". A voucher can only be issued once the "
                            + "booking is confirmed with the supplier.", HttpStatus.CONFLICT);
        }

        boolean reissue = row.getVoucherStatus() == VoucherStatus.REVOKED;
        row.setVoucherStatus(VoucherStatus.ISSUED);
        row.setVoucherIssuedAt(LocalDateTime.now());
        row.setVoucherRevokedAt(null);
        if (row.getVoucherNumber() == null || row.getVoucherNumber().isBlank()) {
            row.setVoucherNumber(mintVoucherNumber());
        }

        PlatformHotelBooking saved = repository.save(row);
        log.info("Voucher {} {} for marketplace booking {} by superAdmin {}",
                saved.getVoucherNumber(), reissue ? "re-issued" : "issued",
                saved.getBookingCode(), superAdminId);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_VOUCHER_ISSUED, true,
                saved.getTenantId(), saved.getTenantCode(), "MARKETPLACE_BOOKING", saved.getPublicId(),
                (reissue ? "Re-issued" : "Issued") + " voucher " + saved.getVoucherNumber()
                        + " for " + saved.getHotelNameSnapshot());

        return saved;
    }

    /**
     * Withdraw an issued voucher.
     *
     * <p><b>The number is kept.</b> Resetting it to null would erase that this booking was ever
     * vouchered, and a guest may still be holding the printed copy — the front desk quoting
     * {@code HV-2026-1A2B3C4D} must still land on this row when support looks it up.</p>
     */
    @Transactional
    public PlatformHotelBooking revoke(UUID bookingPublicId, String reason, Long superAdminId) {
        PlatformHotelBooking row = lock(bookingPublicId);

        if (row.getVoucherStatus() == VoucherStatus.REVOKED) {
            return row;
        }
        if (row.getVoucherStatus() != VoucherStatus.ISSUED) {
            throw new BusinessException(
                    "No voucher has been issued for this booking, so there is nothing to revoke.",
                    HttpStatus.CONFLICT);
        }

        row.setVoucherStatus(VoucherStatus.REVOKED);
        row.setVoucherRevokedAt(LocalDateTime.now());

        PlatformHotelBooking saved = repository.save(row);
        log.info("Voucher {} revoked for marketplace booking {} by superAdmin {} ({})",
                saved.getVoucherNumber(), saved.getBookingCode(), superAdminId, reason);

        auditRecorder.safeRecord(PlatformAuditAction.MARKETPLACE_VOUCHER_REVOKED, true,
                saved.getTenantId(), saved.getTenantCode(), "MARKETPLACE_BOOKING", saved.getPublicId(),
                "Revoked voucher " + saved.getVoucherNumber()
                        + (reason == null || reason.isBlank() ? "" : " — " + reason));

        return saved;
    }

    /**
     * Render the PDF. In memory, every time — never persisted, never cached, never uploaded.
     *
     * <p>The caller supplies the row, because the two callers resolve it differently and must: the
     * SuperAdmin path reads across tenants, the tenant path reads only its own. Putting the lookup in
     * here would mean one of them got the wrong one.</p>
     */
    public byte[] renderPdf(PlatformHotelBooking booking) {
        if (!booking.getStatus().isCommitted() && booking.getVoucherNumber() == null) {
            throw new BusinessException(
                    "This booking is " + booking.getStatus() + " and has no voucher.",
                    HttpStatus.CONFLICT);
        }
        return renderer.render(booking);
    }

    /**
     * Un-scoped read for the SuperAdmin's own copy of a voucher. <b>Platform realm only</b> — this
     * deliberately has no tenant predicate, and calling it from anything a tenant can reach would
     * hand over another tenant's guest details.
     *
     * <p>Not {@code findByPublicIdForUpdate}: that takes a PESSIMISTIC_WRITE lock, and blocking an
     * in-flight approval so an operator can look at a PDF is the wrong trade. The repository has no
     * plain un-scoped by-publicId finder yet, hence the {@link EntityManager} query.</p>
     */
    @Transactional(readOnly = true)
    public PlatformHotelBooking requireForPlatform(UUID publicId) {
        return entityManager.createQuery("""
                        SELECT b FROM PlatformHotelBooking b
                        WHERE b.publicId = :publicId AND b.deletedAt IS NULL
                        """, PlatformHotelBooking.class)
                .setParameter("publicId", publicId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Booking request not found: " + publicId));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private PlatformHotelBooking lock(UUID publicId) {
        return repository.findByPublicIdForUpdate(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking request not found: " + publicId));
    }

    /**
     * {@code HV-<year>-<8 hex>}, backed by the partial unique index {@code uq_phb_voucher_number}.
     *
     * <p>Eight hex characters is 32 bits, which is small enough that a birthday collision is a real
     * if remote possibility over the life of the table — so the number is probed before it is used
     * rather than left for the index to reject at flush, when the enclosing transaction (holding the
     * booking's row lock) would already be poisoned and un-retryable.</p>
     *
     * <p>The probe goes through the {@link EntityManager} only because
     * {@code PlatformHotelBookingRepository} has no {@code existsByVoucherNumber...} finder yet; when
     * one is added this collapses to a single call.</p>
     */
    private String mintVoucherNumber() {
        String year = String.valueOf(Year.now().getValue());
        for (int attempt = 0; attempt < MINT_ATTEMPTS; attempt++) {
            String candidate = "HV-" + year + "-" + String.format("%08X", RANDOM.nextInt());
            Long taken = entityManager.createQuery("""
                            SELECT COUNT(b) FROM PlatformHotelBooking b
                            WHERE b.voucherNumber = :number AND b.deletedAt IS NULL
                            """, Long.class)
                    .setParameter("number", candidate)
                    .getSingleResult();
            if (taken == 0L) {
                return candidate;
            }
            log.warn("Voucher number {} already taken — reminting (attempt {})", candidate, attempt + 1);
        }
        throw new BusinessException("We couldn't allocate a voucher number. Please try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
