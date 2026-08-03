package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.dto.response.BookingTimelineEventDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingExpense;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceRepository;
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Composes the booking activity timeline from records that already exist — nothing is written.
 *
 * <p>Sources:
 * <ul>
 *   <li><b>Envers</b> ({@code bookings_aud}): consecutive-revision diffs of {@code status} and
 *       {@code paymentStatus}. The audited {@code updatedBy}/{@code updatedAt} columns supply
 *       who/when per revision. Envers failures degrade gracefully — the rest of the timeline
 *       still renders.</li>
 *   <li><b>Payment ledger</b> incl. soft-deleted rows: receipts, refund disbursements, and
 *       "payment removed" events from {@code deletedAt}/{@code deletedBy}.</li>
 *   <li><b>Expense ledger</b> incl. soft-deleted rows: recorded and removed events.</li>
 *   <li><b>Cancellation record</b>: the authoritative cancelled-by/when/why. Envers status flips
 *       to CANCELLED are suppressed when this richer record exists.</li>
 *   <li><b>Tax invoices</b>: issued and cancelled events.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BookingTimelineServiceImpl implements BookingTimelineService {

    private static final Logger log = LogManager.getLogger(BookingTimelineServiceImpl.class);

    private final BookingRepository             bookingRepository;
    private final BookingPaymentRepository      paymentRepository;
    private final BookingExpenseRepository      expenseRepository;
    private final BookingCancellationRepository cancellationRepository;
    private final TaxInvoiceRepository          taxInvoiceRepository;
    private final SubAgentScope                 subAgentScope;
    private final EntityManager                 entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<BookingTimelineEventDTO> getTimeline(UUID bookingPublicId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
        subAgentScope.assertVisible(booking, bookingPublicId);

        List<BookingTimelineEventDTO> events = new ArrayList<>();

        // ── Creation ──────────────────────────────────────────────────────────
        events.add(BookingTimelineEventDTO.builder()
                .type("BOOKING_CREATED")
                .title("Booking created")
                .detail(booking.getBookingCode())
                .actor(booking.getCreatedBy())
                .occurredAt(booking.getCreatedAt())
                .build());

        // ── Cancellation record (authoritative who/when/why) ──────────────────
        BookingCancellation cancellation = cancellationRepository
                .findByBookingIdAndDeletedAtIsNull(booking.getId())
                .orElse(null);
        if (cancellation != null) {
            events.add(BookingTimelineEventDTO.builder()
                    .type("BOOKING_CANCELLED")
                    .title("Booking cancelled")
                    .detail(cancellation.getReason())
                    .actor(cancellation.getCancelledByEmail())
                    .occurredAt(cancellation.getCancelledAt())
                    .build());
        }

        // ── Status history from Envers revision diffs ─────────────────────────
        appendRevisionEvents(events, booking, cancellation != null);

        // ── Payment ledger (money IN + refunds; deleted rows become removals) ─
        for (BookingPayment pay : paymentRepository.findByBookingIdOrderByIdAsc(booking.getId())) {
            boolean refund = pay.isRefund();
            events.add(BookingTimelineEventDTO.builder()
                    .type(refund ? "REFUND_RECORDED" : "PAYMENT_RECORDED")
                    .title(refund ? "Refund disbursed" : "Payment received")
                    .detail(joinParts(pay.getPaymentMethod(), pay.getPaymentType(), pay.getNotes()))
                    .actor(pay.getCreatedBy())
                    .occurredAt(pay.getCreatedAt())
                    .amount(pay.getAmount())
                    .reference(pay.getReference())
                    .build());
            if (pay.getDeletedAt() != null) {
                events.add(BookingTimelineEventDTO.builder()
                        .type("PAYMENT_REMOVED")
                        .title(refund ? "Refund entry removed" : "Payment entry removed")
                        .detail(joinParts(pay.getPaymentMethod(), pay.getReference()))
                        .actor(pay.getDeletedBy())
                        .occurredAt(pay.getDeletedAt())
                        .amount(pay.getAmount())
                        .build());
            }
        }

        // ── Expense ledger (money OUT; deleted rows become removals) ──────────
        //
        // Two authorities reach this, with different reach — the same split BookingExpenseController
        // documents. BOOKING_PROFIT_READ sees every expense; MARKETPLACE_PAYABLE_READ sees only rows
        // carrying a marketplace link, because a platform payable is a price the agent must know to
        // quote, not a margin. Without the second branch, TRAVEL_AGENT places a marketplace order and
        // then finds its payable missing from the very timeline meant to explain the booking.
        boolean fullLedger = hasAuthority("BOOKING_PROFIT_READ");
        if (fullLedger || hasAuthority("MARKETPLACE_PAYABLE_READ")) {
            for (BookingExpense exp : expenseRepository.findByBookingIdOrderByIdAsc(booking.getId())) {
                if (!fullLedger && exp.getMarketplaceBookingPublicId() == null) continue;
                events.add(BookingTimelineEventDTO.builder()
                        .type("EXPENSE_RECORDED")
                        .title("Expense recorded")
                        .detail(joinParts(exp.getDescription(), exp.getVendorName()))
                        .actor(exp.getCreatedBy())
                        .occurredAt(exp.getCreatedAt())
                        .amount(exp.getAmount())
                        .build());
                if (exp.getDeletedAt() != null) {
                    events.add(BookingTimelineEventDTO.builder()
                            .type("EXPENSE_REMOVED")
                            .title("Expense removed")
                            .detail(exp.getDescription())
                            .actor(exp.getDeletedBy())
                            .occurredAt(exp.getDeletedAt())
                            .amount(exp.getAmount())
                            .build());
                }
            }
        }

        // ── Tax invoices (issued / cancelled) ─────────────────────────────────
        for (TaxInvoice invoice : taxInvoiceRepository
                .findByTenantIdAndBookingIdAndDeletedAtIsNullOrderByIdDesc(
                        booking.getTenantId(), booking.getId())) {
            events.add(BookingTimelineEventDTO.builder()
                    .type("INVOICE_ISSUED")
                    .title("Tax invoice issued")
                    .actor(invoice.getIssuedByEmail() != null ? invoice.getIssuedByEmail() : invoice.getCreatedBy())
                    .occurredAt(invoice.getIssuedAt() != null ? invoice.getIssuedAt() : invoice.getCreatedAt())
                    .amount(invoice.getInvoiceTotal())
                    .reference(invoice.getInvoiceNumber())
                    .build());
            if (invoice.getCancelledAt() != null) {
                events.add(BookingTimelineEventDTO.builder()
                        .type("INVOICE_CANCELLED")
                        .title("Tax invoice cancelled")
                        .detail(invoice.getCancelReason())
                        .occurredAt(invoice.getCancelledAt())
                        .reference(invoice.getInvoiceNumber())
                        .build());
            }
        }

        events.sort(Comparator.comparing(
                BookingTimelineEventDTO::getOccurredAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return events;
    }

    private boolean hasAuthority(String authority) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /**
     * Walks the booking's Envers revisions in order and emits an event whenever {@code status} or
     * {@code paymentStatus} differs from the previous revision. Best-effort: an Envers problem
     * (e.g. audit tables not yet populated) must never take the whole timeline down.
     */
    private void appendRevisionEvents(
            List<BookingTimelineEventDTO> events, Booking booking, boolean hasCancellationRecord) {
        try {
            AuditReader reader = AuditReaderFactory.get(entityManager);
            @SuppressWarnings("unchecked")
            List<Object[]> revisions = reader.createQuery()
                    .forRevisionsOfEntity(Booking.class, false, false)
                    .add(AuditEntity.id().eq(booking.getId()))
                    .addOrder(AuditEntity.revisionNumber().asc())
                    .getResultList();

            Booking prev = null;
            for (Object[] row : revisions) {
                Booking snap = (Booking) row[0];
                if (prev != null) {
                    if (snap.getStatus() != prev.getStatus()
                            // The cancellation record is the richer event for the CANCELLED flip.
                            && !(hasCancellationRecord && snap.getStatus() == BookingStatus.CANCELLED)) {
                        events.add(BookingTimelineEventDTO.builder()
                                .type("STATUS_CHANGED")
                                .title("Status changed")
                                .detail(label(prev.getStatus()) + " → " + label(snap.getStatus()))
                                .actor(snap.getUpdatedBy())
                                .occurredAt(revisionTime(snap, row))
                                .build());
                    }
                    if (snap.getPaymentStatus() != prev.getPaymentStatus()) {
                        events.add(BookingTimelineEventDTO.builder()
                                .type("PAYMENT_STATUS_CHANGED")
                                .title("Payment status changed")
                                .detail(label(prev.getPaymentStatus()) + " → " + label(snap.getPaymentStatus()))
                                .actor(snap.getUpdatedBy())
                                .occurredAt(revisionTime(snap, row))
                                .build());
                    }
                }
                prev = snap;
            }
        } catch (Exception ex) {
            log.warn("Envers revision walk failed for booking {} — timeline degrades to ledger events only: {}",
                    booking.getPublicId(), ex.getMessage());
        }
    }

    /** The revision's snapshotted updatedAt, falling back to the Envers revision timestamp. */
    private static LocalDateTime revisionTime(Booking snap, Object[] row) {
        if (snap.getUpdatedAt() != null) {
            return snap.getUpdatedAt();
        }
        if (row.length > 1 && row[1] instanceof DefaultRevisionEntity rev && rev.getRevisionDate() != null) {
            return LocalDateTime.ofInstant(rev.getRevisionDate().toInstant(), ZoneId.systemDefault());
        }
        return snap.getCreatedAt();
    }

    /** "PROPOSAL_SENT" → "Proposal sent" — display label for an enum constant. */
    private static String label(Enum<?> value) {
        if (value == null) return "—";
        String s = value.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Joins non-blank parts with " · " — null when nothing survives. */
    private static String joinParts(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
