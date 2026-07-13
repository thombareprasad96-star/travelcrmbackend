package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CancelBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.LeadConversionRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.enums.CancelAction;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.mapper.BookingMapper;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.enums.RefundStatus;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.cancellation.service.CancellationCalculator;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.cancellation.service.CancellationPolicyResolver;
import com.crm.travelcrm.booking.specification.BookingSpecification;
import com.crm.travelcrm.booking.util.BookingCodeGenerator;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.event.LeadSoftDeletedEvent;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.util.PhoneNormalizer;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.customer.util.CustomerCodeGenerator;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.domain.enums.NotificationType;
import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LogManager.getLogger(BookingServiceImpl.class);

    // Tax rates are externalised so they can be tuned per environment without a redeploy.
    // NOTE: these are still flat rates applied to every booking. Correct Indian TCS is
    // slabbed (nil domestic; 5% up to ₹7L, 20% above, under LRS) and GST varies by service —
    // applying that properly needs a domestic/overseas + product classification the booking
    // model does not yet carry. Externalising the rate is the safe first step, not the whole fix.
    @Value("${app.booking.gst-rate:0.05}")
    private BigDecimal gstRate;

    @Value("${app.booking.tcs-rate:0.05}")
    private BigDecimal tcsRate;

    private final BookingRepository        bookingRepository;
    private final BookingPaymentRepository paymentRepository;
    private final BookingMapper        bookingMapper;
    private final BookingCodeGenerator bookingCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerRepository   customerRepository;
    private final CustomerCodeGenerator customerCodeGenerator;
    private final LeadRepository       leadRepository;
    private final LeadAccessGuard      leadAccessGuard;
    private final QuotationRepository  quotationRepository;
    private final TenantRepository     tenantRepository;
    private final CancellationPolicyResolver policyResolver;
    private final CancellationCalculator cancellationCalculator;
    private final BookingCancellationRepository cancellationRepository;
    private final CancellationDocumentService cancellationDocumentService;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO create(CreateBookingRequestDTO request) {
        log.info("Creating new booking for customer: {}", request.getCustomerId());

        Long tenantId = requireTenantId();

        Booking booking = bookingMapper.toEntity(request);
        booking.setTenantId(tenantId);
        booking.setBookingCode(bookingCodeGenerator.generate(tenantId));
        booking.setStatus(BookingStatus.PENDING);

        // Monthly booking-quota gate (hard 403 once the plan cap for the target month is reached).
        enforceBookingQuota(tenantId, booking.getBookingDate());

        // Validate cross-aggregate references (no DB FK) and snapshot the resolved values.
        Customer customer = customerRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(request.getCustomerId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found: " + request.getCustomerId()));
        booking.setCustomerId(customer.getId());
        booking.setCustomerNameSnapshot(customer.getName());

        // Destination is sent as a free-text name (no id); snapshot it so the NOT NULL holds.
        booking.setDestinationSnapshot(request.getDestination());

        if (request.getLeadId() != null) {
            if (!leadRepository.existsByIdAndTenantIdAndDeletedAtIsNull(request.getLeadId(), tenantId)) {
                throw new ResourceNotFoundException("Lead not found: " + request.getLeadId());
            }
            booking.setLeadId(request.getLeadId());
        }

        BigDecimal initialPaid = request.getPaidAmount() != null
                ? request.getPaidAmount() : BigDecimal.ZERO;
        calculateAndApplyFinancials(booking, request.getCustomerAmount(),
                request.getVendorCost(), initialPaid);

        // Pin the governing cancellation policy NOW (immune to later policy edits). A direct booking
        // has no source quotation, so the tenant company default (as-of the booking date) applies.
        pinCancellationPolicy(booking, null, tenantId);

        Booking saved = bookingRepository.save(booking);
        // An initial payment entered on the create form must appear in the ledger too, or the invoice
        // would show "Paid ₹X" over an empty Payments-Received table (paidAmount vs ledger divergence).
        if (initialPaid.signum() > 0) {
            recordPaymentLedgerRow(saved, initialPaid, "Opening balance",
                    saved.getBookingDate(), null, "Initial payment recorded at booking creation");
        }
        log.info("Booking created successfully with code: {}", saved.getBookingCode());
        publishBookingEvent(NotificationType.BOOKING_CREATED, saved,
                "New Booking: " + saved.getBookingCode(),
                "A new booking " + saved.getBookingCode() + " was created");
        return bookingMapper.toResponse(saved);
    }

    /**
     * Monthly booking-quota gate. Rejects a new booking that would exceed the tenant's
     * {@code maxBookingsPerMonth} for the target booking's calendar month ({@code null}/≤0 =
     * unlimited). Mirrors the user-seat cap in {@code UserServiceImpl}; soft-alerting as the tenant
     * approaches the limit is handled separately by the usage-alert scheduler. {@code Tenant} is
     * platform-level (no tenant filter), so a direct {@code findById} is correct here.
     */
    private void enforceBookingQuota(Long tenantId, LocalDate bookingDate) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) return;
        Integer limit = tenant.getMaxBookingsPerMonth();
        if (limit == null || limit <= 0) return;   // unlimited

        LocalDate date = bookingDate != null ? bookingDate : LocalDate.now();
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate nextMonthStart = monthStart.plusMonths(1);
        long used = bookingRepository.countByTenantForMonth(tenantId, monthStart, nextMonthStart);
        if (used >= limit) {
            throw new BusinessException(
                    "Your plan allows up to " + limit + " new bookings for "
                            + monthStart.getMonth() + " " + monthStart.getYear()
                            + ". Upgrade your plan or contact support to add more.",
                    HttpStatus.FORBIDDEN);
        }
    }

    // ── Convert Lead → Booking ────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO convertLeadToBooking(UUID leadPublicId, LeadConversionRequestDTO request) {
        Long tenantId = requireTenantId();
        log.info("Converting lead {} to booking", leadPublicId);

        // Monthly booking-quota gate (same cap as direct create; convert also produces a new booking).
        enforceBookingQuota(tenantId, request.getBookingDate());

        // Tenant + row-level scope (LEAD_UPDATE — converting mutates the lead). Returns the
        // managed Lead so the stage flip below participates in this same transaction.
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_UPDATE");

        // Duplicate guard — never silently create a SECOND active booking for the same lead.
        // Re-submits and double-clicks are rejected with a friendly 409 naming the existing one.
        // CANCELLED bookings are excluded: cancelling reopens the lead (REOPENED) but retains the
        // booking row, so a reopened lead must be re-convertible even though its old cancelled
        // booking still exists.
        bookingRepository.findFirstByLeadIdAndTenantIdAndStatusNotAndDeletedAtIsNullOrderByIdDesc(
                        lead.getId(), tenantId, BookingStatus.CANCELLED)
                .ifPresent(existing -> {
                    throw new BusinessException(
                            "This lead is already converted to booking " + existing.getBookingCode()
                                    + ". Open that booking instead of creating another.",
                            HttpStatus.CONFLICT);
                });

        // Optional source quotation — validate it belongs to this lead + tenant before linking.
        UUID sourceQuotationPublicId = null;
        UUID quotationPolicyPublicId = null;   // the structured cancellation policy the quote was priced under
        if (request.getQuotationPublicId() != null) {
            Quotation quotation = quotationRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(request.getQuotationPublicId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Quotation not found: " + request.getQuotationPublicId()));
            boolean belongsToLead = Objects.equals(quotation.getLeadId(), lead.getId())
                    || Objects.equals(quotation.getLeadPublicId(), lead.getPublicId());
            if (!belongsToLead) {
                throw new BusinessException(
                        "The selected quotation does not belong to this lead.", HttpStatus.BAD_REQUEST);
            }
            sourceQuotationPublicId = quotation.getPublicId();
            quotationPolicyPublicId = quotation.getCancellationPolicyPublicId();
        }

        // Resolve or create the customer from the lead (phone is the per-tenant natural key).
        Customer customer = resolveOrCreateCustomer(lead, request.getCustomerName(), tenantId);

        // Build the booking, carrying over the reviewed details + source back-links.
        Booking booking = Booking.builder()
                .tenantId(tenantId)
                .bookingCode(bookingCodeGenerator.generate(tenantId))
                .customerId(customer.getId())
                .customerNameSnapshot(request.getCustomerName())
                .destinationSnapshot(request.getDestination())
                .leadId(lead.getId())
                .sourceLeadPublicId(lead.getPublicId())
                .sourceQuotationPublicId(sourceQuotationPublicId)
                .status(BookingStatus.PENDING)
                .bookingDate(request.getBookingDate() != null ? request.getBookingDate() : LocalDate.now())
                .travelDate(request.getTravelDate())
                // customerAmount / vendorCost are stored as-is; the helper derives gst/tcs/total/profit.
                .customerAmount(request.getCustomerAmount())
                .vendorCost(request.getVendorCost())
                .services(request.getServices() != null
                        ? new ArrayList<>(request.getServices())
                        : new ArrayList<>())
                .build();

        BigDecimal paid = request.getPaidAmount() != null ? request.getPaidAmount() : BigDecimal.ZERO;
        calculateAndApplyFinancials(booking, request.getCustomerAmount(), request.getVendorCost(), paid);

        // Pin the governing cancellation policy: the exact version the quotation was priced under
        // (so the customer is charged the terms they were quoted), else the company default.
        pinCancellationPolicy(booking, quotationPolicyPublicId, tenantId);

        Booking saved = bookingRepository.save(booking);
        // Carry an initial payment into the ledger so paidAmount and the receipts table reconcile.
        if (paid.signum() > 0) {
            recordPaymentLedgerRow(saved, paid, "Opening balance",
                    saved.getBookingDate(), null, "Initial payment recorded during lead conversion");
        }

        // Flip the lead to CONVERTED — keep it for history, stamp the back-link to the booking.
        lead.setLeadStage(LeadStage.CONVERTED);
        lead.setConvertedAt(LocalDateTime.now());
        lead.setConvertedBookingPublicId(saved.getPublicId());
        leadRepository.save(lead);

        log.info("Lead {} converted to booking {} (tenant {})",
                leadPublicId, saved.getBookingCode(), tenantId);

        publishBookingEvent(NotificationType.BOOKING_CREATED, saved,
                "Lead converted: " + saved.getBookingCode(),
                lead.getCustomerName() + " was converted to booking " + saved.getBookingCode());

        return bookingMapper.toResponse(saved);
    }

    /**
     * Resolve the tenant customer for a conversion, in order:
     *   1. Reuse the live customer with this (normalised) phone — a repeat customer.
     *   2. Else restore-and-reuse a trashed customer with this phone — avoids an INSERT that
     *      would collide with the (tenant_id, phone) partial unique index and 500 (the failure
     *      that hit "convert → cancel → same customer converts again").
     *   3. Else create a fresh customer, stamped with provenance ({@code createdFromLeadId}) so
     *      cancel-cleanup can safely reclaim ONLY the customers conversion created itself.
     * Phone is normalised the same way everywhere ({@link PhoneNormalizer}) so a lead never
     * spawns a duplicate of an otherwise-identical customer, and a blank phone is rejected up
     * front instead of creating an unmatchable / NOT-NULL-violating row.
     */
    private Customer resolveOrCreateCustomer(Lead lead, String name, Long tenantId) {
        String phone = PhoneNormalizer.normalize(lead.getPhone());
        if (PhoneNormalizer.isBlank(phone)) {
            throw new BusinessException(
                    "This lead has no phone number, so it cannot be converted to a booking. "
                            + "Add a phone number to the lead first.",
                    HttpStatus.BAD_REQUEST);
        }
        String resolvedName = (name != null && !name.isBlank()) ? name : lead.getCustomerName();

        // 1. Live customer — reuse.
        Optional<Customer> live = customerRepository
                .findByPhoneAndTenantIdAndDeletedAtIsNull(phone, tenantId);
        if (live.isPresent()) {
            return live.get();
        }

        // 2. Trashed customer with the same phone — restore rather than insert a colliding row.
        Optional<Customer> trashed = customerRepository
                .findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(phone, tenantId);
        if (trashed.isPresent()) {
            Customer revived = trashed.get();
            revived.restore();
            Customer saved = customerRepository.save(revived);
            log.info("Restored trashed customer {} for conversion of lead {}",
                    saved.getCustomerCode(), lead.getPublicId());
            return saved;
        }

        // 3. Brand-new customer — stamp provenance so cancel-cleanup can reclaim it later.
        Customer customer = Customer.builder()
                .tenantId(tenantId)
                .customerCode(customerCodeGenerator.generate(tenantId))
                .name(resolvedName)
                .phone(phone)
                .email(lead.getEmail())
                .createdFromLeadId(lead.getId())
                .build();
        Customer created = customerRepository.save(customer);
        log.info("Created customer {} from lead {} during conversion",
                created.getCustomerCode(), lead.getPublicId());
        return created;
    }

    // ── Get by ID ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getById(UUID publicId) {
        return bookingMapper.toResponse(findActiveByPublicId(publicId));
    }

    // ── Get by Code ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getByCode(String code) {
        Booking booking = bookingRepository.findByBookingCodeAndDeletedAtIsNull(code)
                .orElseThrow(() -> new BookingNotFoundException(code));
        return bookingMapper.toResponse(booking);
    }

    // ── Get All (Paginated) ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<BookingResponseDTO> getAll(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable  = PageRequest.of(page, size, sort);
        Page<Booking> bookingPage = bookingRepository.findAll(
                BookingSpecification.isActive(), pageable);

        List<BookingResponseDTO> content = bookingPage.getContent()
                .stream()
                .map(bookingMapper::toResponse)
                .toList();

        return PagedApiResponse.of(
                "Bookings fetched successfully",
                content,
                PaginationMeta.from(bookingPage, sortBy, sortDir)
        );
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO update(UUID publicId, UpdateBookingRequestDTO request) {
        log.info("Updating booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);
        bookingMapper.updateEntity(request, booking);   // applies non-null DTO fields → entity

        // Destination is a free-text snapshot (mapper ignores it) — apply it explicitly.
        if (request.getDestination() != null) {
            booking.setDestinationSnapshot(request.getDestination());
        }

        // Status is server-owned (mapper ignores it). Apply here behind lifecycle guards so an
        // edit can never silently skip the cancel() flow or mutate a locked booking.
        boolean statusChanged = applyStatusOnUpdate(booking, request.getStatus());

        // paidAmount is owned by the payment ledger (POST/DELETE /payments, PATCH /payment), NOT by
        // this edit form — recording payments here as an absolute set is what let paidAmount diverge
        // from the itemised ledger rows. A booking edit only recomputes the DERIVED money fields: when
        // the amounts change, refresh gst / tcs / totalPayable / netProfit and re-derive paymentStatus
        // from the current (ledger-owned) paidAmount. Any paidAmount sent by the client is ignored.
        if (request.getCustomerAmount() != null || request.getVendorCost() != null) {
            recomputeTotals(booking, booking.getCustomerAmount(), booking.getVendorCost());

            // Reducing the amount below what has already been paid is inconsistent. Throwing rolls back.
            if (booking.getPaidAmount().compareTo(booking.getTotalPayable()) > 0) {
                throw new BusinessException(
                        "Paid amount ₹" + booking.getPaidAmount()
                                + " exceeds total payable ₹" + booking.getTotalPayable());
            }
        }

        Booking saved = bookingRepository.save(booking);

        // Mirror updateStatus(): notify on a real status change so the bell/feed stays in sync.
        if (statusChanged) {
            publishBookingEvent(statusEventType(saved.getStatus()), saved,
                    "Booking " + saved.getStatus() + ": " + saved.getBookingCode(),
                    "Booking " + saved.getBookingCode() + " status changed to " + saved.getStatus());
        }

        return bookingMapper.toResponse(saved);
    }

    /**
     * Apply a client-requested status change coming from the general update, guarding the
     * booking lifecycle. Returns {@code true} only if the status actually changed (so the
     * caller can fire the notification).
     *
     * <p>CANCELLED is refused here — it must go through {@code cancel()}, which reopens the
     * linked lead and reclaims a conversion-created customer; setting it via a field edit would
     * skip all of that. A COMPLETED or CANCELLED booking is terminal, so its status is locked.</p>
     */
    private boolean applyStatusOnUpdate(Booking booking, BookingStatus newStatus) {
        if (newStatus == null || newStatus == booking.getStatus()) {
            return false;
        }
        if (newStatus == BookingStatus.CANCELLED) {
            throw new BusinessException(
                    "To cancel a booking, use the cancel action — it reopens the linked lead and "
                            + "cleans up a conversion-created customer. Setting CANCELLED here would skip that.",
                    HttpStatus.CONFLICT);
        }
        if (booking.getStatus() == BookingStatus.COMPLETED
                || booking.getStatus() == BookingStatus.CANCELLED) {
            throw new BusinessException(
                    "A " + booking.getStatus() + " booking is locked; its status can no longer be changed.",
                    HttpStatus.CONFLICT);
        }
        booking.setStatus(newStatus);
        return true;
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updateStatus(UUID publicId, StatusUpdateRequestDTO request) {
        log.info("Updating status for booking publicId: {} to {}", publicId, request.getStatus());

        Booking booking = findActiveByPublicId(publicId);

        // Route through the SAME lifecycle guard the general PUT uses. This refuses a CANCELLED
        // transition (cancel must go through cancel() → moveBackToLead + derived-customer cleanup,
        // and is gated by the stricter BOOKING_CANCEL), locks terminal COMPLETED/CANCELLED, and
        // no-ops a same-status request — so a plain BOOKING_UPDATE user can no longer cancel,
        // un-cancel, or reopen a terminal booking through this endpoint.
        boolean statusChanged = applyStatusOnUpdate(booking, request.getStatus());
        if (!statusChanged) {
            return bookingMapper.toResponse(booking);   // requested status == current: nothing to do
        }

        Booking saved = bookingRepository.save(booking);
        publishBookingEvent(statusEventType(saved.getStatus()), saved,
                "Booking " + saved.getStatus() + ": " + saved.getBookingCode(),
                "Booking " + saved.getBookingCode() + " status changed to " + saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    // ── Cancel (with explicit lead handling) ──────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO cancel(UUID publicId, CancelBookingRequestDTO request) {
        Long tenantId = requireTenantId();
        Booking booking = findActiveByPublicId(publicId);

        // A completed journey is locked: its lead must not revert and its history must not be erased.
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "A completed booking cannot be cancelled.", HttpStatus.CONFLICT);
        }
        // Advisory pre-check (fast, friendly). The authoritative double-cancel guard is the UNIQUE
        // booking_id on booking_cancellations + the @Version bump on the booking row below.
        if (booking.getStatus() == BookingStatus.CANCELLED
                || cancellationRepository.existsByBookingIdAndDeletedAtIsNull(booking.getId())) {
            throw new BusinessException("This booking is already cancelled.", HttpStatus.CONFLICT);
        }

        // ── Compute the cancellation charge under the booking's governing policy ──
        CancellationPolicy policy = resolveGoverningPolicyForCancel(booking, tenantId);

        BigDecimal override = request.getOverrideChargeBase();
        if (override != null) {
            // Overriding/waiving the computed charge moves money vs the policy — elevated gate.
            requireAuthority("BOOKING_REFUND",
                    "Overriding or waiving the cancellation charge requires refund permission.");
        }
        CancellationQuote quote = cancellationCalculator.calculate(
                booking, policy, LocalDate.now(), override, request.getVendorRecoverable());

        // No configured policy ⇒ a zero charge / full refund — must not be a silent default.
        if (quote.isNoPolicy()) {
            requireAuthority("BOOKING_REFUND",
                    "No cancellation policy is configured for this booking; cancelling it requires "
                            + "refund permission so the zero-charge/full-refund is an explicit decision.");
        }

        // Persist the immutable financial record. The UNIQUE booking_id makes a concurrent/double
        // cancel fail here (rolling back the whole transaction), so a refund can never be issued twice.
        BookingCancellation record = buildCancellationRecord(booking, quote, policy, request);
        cancellationRepository.save(record);

        // Mint the credit/debit note (number + frozen snapshot) inside this transaction so the number
        // is reserved atomically with the money; the PDF bytes render lazily on first fetch. Sets the
        // note number + doc id back on the record.
        cancellationDocumentService.issueCancellationNote(booking, record, quote);

        // Legacy booking with no pinned policy: freeze the resolved version now so it's reproducible.
        if (booking.getCancellationPolicyPublicId() == null && policy != null) {
            booking.setCancellationPolicyPublicId(policy.getPublicId());
            booking.setCancellationPolicyVersion(policy.getVersion());
        }

        // ── Lead / derived-customer disposition (unchanged behaviour) ──
        handleDerivedCustomerOnCancel(booking, tenantId);
        if (request.getAction() == CancelAction.PERMANENT_DELETE_LEAD) {
            requireAuthority("LEAD_PERMANENT_DELETE",
                    "You don't have permission to remove this lead. Please contact your administrator.");
            trashLeadOnCancel(booking, tenantId);
        } else {
            moveBackToLead(booking, tenantId);
        }

        // The booking is ALWAYS retained — only its status changes. Setting status + refundedAmount
        // dirties the row so the @Version optimistic lock serializes two concurrent cancels (the
        // loser gets a 409 instead of a second cancellation record).
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setRefundedAmount(BigDecimal.ZERO);   // nothing disbursed yet; the refund flow accrues it
        Booking saved = bookingRepository.save(booking);

        // Notify only AFTER the money + record commit, so a rolled-back cancel never pushes a
        // "cancelled" SSE / notification row.
        publishBookingEventAfterCommit(NotificationType.BOOKING_CANCELLED, saved,
                "Booking cancelled: " + saved.getBookingCode(),
                "Booking " + saved.getBookingCode() + " was cancelled — retained "
                        + quote.getTotalRetained() + ", "
                        + (quote.isCustomerOwes()
                            ? "customer owes " + quote.getCustomerBalanceOwed()
                            : "refund due " + quote.getRefundToCustomer()));
        log.info("Booking {} cancelled (tenant {}) — retained {}, refundDue {} [{}]",
                saved.getBookingCode(), tenantId, quote.getTotalRetained(),
                quote.getRefundDue(), record.getRefundStatus());
        return bookingMapper.toResponse(saved);
    }

    /**
     * The policy governing this booking's cancellation: the exact version pinned at creation, else
     * (legacy/unpinned) the company default in force as of the booking date. May be null.
     */
    private CancellationPolicy resolveGoverningPolicyForCancel(Booking booking, Long tenantId) {
        CancellationPolicy policy = null;
        if (booking.getCancellationPolicyPublicId() != null) {
            policy = policyResolver.loadPinned(tenantId, booking.getCancellationPolicyPublicId()).orElse(null);
        }
        if (policy == null) {
            policy = policyResolver.companyDefaultAsOf(tenantId, booking.getBookingDate()).orElse(null);
        }
        return policy;
    }

    /** Freeze the computed quote + provenance + who/when into the immutable cancellation record. */
    private BookingCancellation buildCancellationRecord(Booking booking, CancellationQuote quote,
                                                        CancellationPolicy policy,
                                                        CancelBookingRequestDTO request) {
        boolean refundDueOwed = quote.getRefundDue() != null && quote.getRefundDue().signum() > 0;
        Long actorId = currentUserId();
        return BookingCancellation.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .bookingCode(booking.getBookingCode())
                .cancelledByUserId(actorId)
                .cancelledByEmail(currentUserEmail())
                .cancelledAt(LocalDateTime.now())
                .cancelDate(quote.getCancelDate())
                .reason(request.getReason())
                .leadAction(request.getAction())
                .policyPublicId(policy != null ? policy.getPublicId() : null)
                .policyVersion(policy != null ? policy.getVersion() : null)
                .noPolicy(quote.isNoPolicy())
                .appliedBandMinDays(quote.getAppliedBandMinDays())
                .appliedDeductionType(quote.getAppliedDeductionType())
                .appliedDeductionValue(quote.getAppliedDeductionValue())
                .daysBeforeDeparture(quote.getDaysBeforeDeparture())
                .baseConsidered(quote.getBaseConsidered())
                .systemComputedChargeBase(quote.getSystemComputedChargeBase())
                .finalChargeBase(quote.getChargeBase())
                .overrideApplied(quote.isOverrideApplied())
                .overriddenByUserId(quote.isOverrideApplied() ? actorId : null)
                .overrideReason(quote.isOverrideApplied() ? request.getOverrideReason() : null)
                .gstOnCharge(quote.getGstOnCharge())
                .tcsRetained(quote.getTcsRetained())
                .totalRetained(quote.getTotalRetained())
                .paidAtCancel(quote.getPaidAmount())
                .refundDue(quote.getRefundDue())
                .customerBalanceOwed(quote.getCustomerBalanceOwed())
                .customerOwes(quote.isCustomerOwes())
                .refundStatus(refundDueOwed ? RefundStatus.PENDING : RefundStatus.NOT_APPLICABLE)
                .sunkVendorCost(quote.getSunkVendorCost())
                .vendorRecoverable(quote.getVendorRecoverable())
                .revisedNetProfit(quote.getRevisedNetProfit())
                .build();
    }

    /** MOVE_TO_LEAD: re-activate the source lead (REOPENED), keeping the booking↔lead link. */
    private void moveBackToLead(Booking booking, Long tenantId) {
        Long leadId = booking.getLeadId();
        if (leadId == null) {
            log.info("Cancel/MOVE_TO_LEAD: booking {} has no associated lead — cancelling only",
                    booking.getBookingCode());
            return;
        }
        leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(leadId, tenantId).ifPresent(lead -> {
            lead.setLeadStage(LeadStage.REOPENED);
            lead.setConvertedAt(null);
            lead.setConvertedBookingPublicId(null);
            leadRepository.save(lead);
            log.info("Lead {} reopened after cancelling booking {}",
                    lead.getPublicId(), booking.getBookingCode());
        });
    }

    /**
     * PERMANENT_DELETE_LEAD: soft-delete (Trash) the associated lead instead of the old hard
     * delete — fully recoverable. The lead's quotations cascade-trash via {@link LeadSoftDeletedEvent}
     * (the same path as a normal lead delete), so nothing is silently orphaned and the logic lives in
     * one place. The booking keeps its lead link, so restoring the lead from Trash reconnects them;
     * the retained cancelled booking stays meaningful via its own customer/destination snapshots.
     *
     * <p>"Permanent" is now a label, not a hard delete: only Trash delete-now and the 30-day
     * auto-purge ever remove a lead physically.</p>
     */
    private void trashLeadOnCancel(Booking booking, Long tenantId) {
        Long leadId = booking.getLeadId();
        if (leadId == null) {
            log.info("Cancel/remove-lead: booking {} has no associated lead — cancelling only",
                    booking.getBookingCode());
            return;
        }
        leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(leadId, tenantId).ifPresent(lead -> {
            lead.softDelete(currentUserEmail());
            leadRepository.save(lead);
            // Cascade-trash the lead's quotations through the shared event (no manual loop here).
            eventPublisher.publishEvent(new LeadSoftDeletedEvent(lead.getId(), tenantId));
            log.warn("Lead {} moved to Trash while cancelling booking {} (recoverable)",
                    lead.getPublicId(), booking.getBookingCode());
        });
    }

    /**
     * Customer-removal guard for cancel. A customer is moved to Trash (soft-deleted) ONLY when
     * BOTH hold:
     *   (a) it was auto-created by a lead→booking conversion ({@code createdFromLeadId} set) —
     *       a manually-entered customer is NEVER reclaimed just because a booking was cancelled;
     *   (b) it has no other active (non-trashed) booking — otherwise it's a repeat customer and
     *       is preserved, the cancelled booking simply staying in its history ({@code customer_id}
     *       is NOT NULL, so the link is kept rather than nulled).
     * Always recoverable, never a hard delete. A COMPLETED booking can't reach here (it can't be
     * cancelled) and counts as "active" for (b), so any customer with completed-journey history —
     * even a conversion-derived one — is inherently protected.
     */
    private void handleDerivedCustomerOnCancel(Booking booking, Long tenantId) {
        Long customerId = booking.getCustomerId();
        if (customerId == null) return;

        long otherActive = bookingRepository
                .countByCustomerIdAndTenantIdAndDeletedAtIsNullAndIdNot(customerId, tenantId, booking.getId());
        if (otherActive > 0) {
            log.info("Cancel: customer {} retained ({} other active booking(s)); booking {} kept linked",
                    customerId, otherActive, booking.getBookingCode());
            return;
        }
        customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId).ifPresent(customer -> {
            // Provenance gate: only reclaim customers conversion created itself. A hand-entered
            // customer (createdFromLeadId == null) whose first/only booking is cancelled stays.
            if (customer.getCreatedFromLeadId() == null) {
                log.info("Cancel: customer {} retained (manually created, not conversion-derived); booking {} kept linked",
                        customer.getCustomerCode(), booking.getBookingCode());
                return;
            }
            customer.softDelete(currentUserEmail());
            customerRepository.save(customer);
            log.info("Cancel: derived customer {} moved to Trash (no other active bookings)",
                    customer.getCustomerCode());
        });
    }

    /** Programmatic authority check for conditional gating within a single endpoint. */
    private void requireAuthority(String authority, String denyMessage) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean has = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
        if (!has) {
            throw new BusinessException(denyMessage, HttpStatus.FORBIDDEN);
        }
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updatePayment(UUID publicId, PaymentUpdateRequestDTO request) {
        log.info("Updating payment for booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);

        // ✅ Accumulate — this is an incremental payment, not a replacement
        BigDecimal newPaidAmount = booking.getPaidAmount().add(request.getAmount());

        if (newPaidAmount.compareTo(booking.getTotalPayable()) > 0) {
            throw new BusinessException(
                    "Total paid ₹" + newPaidAmount
                            + " exceeds total payable ₹" + booking.getTotalPayable());
        }

        booking.setPaidAmount(newPaidAmount);
        // pendingAmount is a @Transient getter; paymentStatus never downgrades a terminal REFUNDED.
        booking.setPaymentStatus(
                derivePaymentStatus(newPaidAmount, booking.getTotalPayable(), booking.getPaymentStatus()));

        Booking saved = bookingRepository.save(booking);
        // Record the receipt in the ledger so it shows in GET /payments and the invoice's
        // Payments-Received table — this PATCH path used to move paidAmount without a ledger row.
        recordPaymentLedgerRow(saved, request.getAmount(), "Payment",
                request.getPaymentDate(), request.getPaymentReference(), request.getNotes());
        publishBookingEvent(NotificationType.BOOKING_PAYMENT_UPDATED, saved,
                "Payment updated: " + saved.getBookingCode(),
                "₹" + request.getAmount() + " received for booking " + saved.getBookingCode()
                        + " (" + saved.getPaymentStatus() + ")");
        return bookingMapper.toResponse(saved);
    }
    // ── Soft Delete ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        log.info("Soft deleting booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);

        if (booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "Cannot delete a " + booking.getStatus() + " booking. Cancel it first.");
        }

        booking.softDelete(currentUserEmail());

        bookingRepository.save(booking);
        log.info("Booking soft deleted: {}", booking.getBookingCode());
    }

    // ── Get by Customer ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getByCustomerId(Long customerId) {
        return bookingRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> search(String keyword) {
        Specification<Booking> spec = BookingSpecification.isActive()
                .and(BookingSpecification.search(keyword));

        return bookingRepository.findAll(spec)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> filter(
            BookingStatus status,
            PaymentStatus paymentStatus,
            Integer bookingMonth,
            Integer travelMonth,
            Long customerId,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        Specification<Booking> spec = BookingSpecification.filter(
                status, paymentStatus, bookingMonth, travelMonth,
                customerId, fromDate, toDate, minAmount, maxAmount);

        return bookingRepository.findAll(spec)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponseDTO getStats() {
        return BookingStatsResponseDTO.builder()
                .totalBookings(bookingRepository.countByDeletedAtIsNull())
                .confirmedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CONFIRMED))
                .pendingBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.PENDING))
                .cancelledBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CANCELLED))
                .completedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.COMPLETED))
                .refundedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.REFUNDED))
                .totalRevenue(bookingRepository.sumTotalRevenue())
                .totalCollected(bookingRepository.sumTotalCollected())
                .totalPending(bookingRepository.sumTotalPending())
                .totalRefundAmount(bookingRepository.sumTotalRefund())
                .netProfit(bookingRepository.sumNetProfit())
                .gstCollected(bookingRepository.sumGstCollected())
                .tcsCollected(bookingRepository.sumTcsCollected())
                .build();
    }

    // ── Page Summary ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingPageSummaryResponseDTO getPageSummary(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Booking> bookingPage = bookingRepository.findAll(
                BookingSpecification.isActive(), pageable);

        // Money summary excludes CANCELLED/REFUNDED (mirrors the /stats aggregates) so a cancelled
        // booking's amount/balance never inflates revenue or receivables. isActive() is deliberately
        // left alone — the paginated list, search and CSV export must still SHOW cancelled bookings.
        List<Booking> bookings = bookingPage.getContent().stream()
                .filter(b -> b.getStatus() != BookingStatus.CANCELLED
                        && b.getStatus() != BookingStatus.REFUNDED)
                .toList();

        BigDecimal pageRevenue = bookings.stream()
                .map(Booking::getCustomerAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageProfit = bookings.stream()
                .map(Booking::getNetProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageGST = bookings.stream()
                .map(Booking::getGst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageTCS = bookings.stream()
                .map(Booking::getTcs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pagePending = bookings.stream()
                .map(Booking::getPendingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return BookingPageSummaryResponseDTO.builder()
                .totalRevenue(pageRevenue)      // ✅ was: pageRevenue
                .netProfit(pageProfit)          // ✅ was: pageProfit
                .gstCollected(pageGST)          // ✅ was: pageGST
                .tcsCollected(pageTCS)          // ✅ was: pageTCS
                .totalPending(pagePending)      // ✅ was: pagePendingAmount
                .build();
    }

    // ── Send Voucher (stub) ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void sendVoucher(UUID publicId) {
        Booking booking = findActiveByPublicId(publicId);
        // Email integration will be wired in future sprint
        log.info("Voucher send requested for booking: {}", booking.getBookingCode());
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    private Booking findActiveByPublicId(UUID publicId) {
        return bookingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BookingNotFoundException(publicId));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — used as the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    private static NotificationType statusEventType(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> NotificationType.BOOKING_CONFIRMED;
            case CANCELLED -> NotificationType.BOOKING_CANCELLED;
            default        -> NotificationType.BOOKING_STATUS_CHANGED;
        };
    }

    /** Fan-out to tenant admins (recipients resolved in the notification module), actor excluded. */
    private void publishBookingEvent(NotificationType type, Booking booking, String title, String message) {
        eventPublisher.publishEvent(buildBookingEvent(type, booking, title, message));
    }

    /**
     * Publish a booking notification only once the surrounding transaction commits. The in-app
     * channel SSE-pushes and writes a DB row synchronously, so publishing inline would fire the
     * notification even when the transaction later rolls back (e.g. the losing side of two concurrent
     * cancels). Falls back to immediate publish if no transaction is active.
     */
    private void publishBookingEventAfterCommit(NotificationType type, Booking booking,
                                                String title, String message) {
        NotifyEvent event = buildBookingEvent(type, booking, title, message);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { eventPublisher.publishEvent(event); }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private NotifyEvent buildBookingEvent(NotificationType type, Booking booking, String title, String message) {
        return NotifyEvent.builder()
                .type(type.name())
                .tenantId(booking.getTenantId())
                .actorUserId(currentUserId())
                .title(title)
                .message(message)
                .referenceType("BOOKING")
                .referencePublicId(booking.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build();
    }

    /**
     * Recompute the derived money fields (gst / tcs / totalPayable / netProfit) and re-derive
     * paymentStatus from the booking's CURRENT paidAmount. Deliberately does NOT touch paidAmount —
     * that is owned by the payment ledger (POST/DELETE /payments, PATCH /payment), never by an edit
     * of the booking's amounts. Keeps totals consistent when customerAmount / vendorCost change.
     */
    private void recomputeTotals(Booking booking, BigDecimal customerAmount, BigDecimal vendorCost) {
        BigDecimal gst          = customerAmount.multiply(gstRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tcs          = customerAmount.multiply(tcsRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPayable = customerAmount.add(gst).add(tcs);
        BigDecimal netProfit    = customerAmount.subtract(vendorCost);

        booking.setGst(gst);
        booking.setTcs(tcs);
        booking.setTotalPayable(totalPayable);
        booking.setNetProfit(netProfit);
        booking.setPaymentStatus(
                derivePaymentStatus(booking.getPaidAmount(), totalPayable, booking.getPaymentStatus()));
    }

    /**
     * Resolve and stamp the cancellation-policy version that governs this booking, once, at creation.
     * {@code quotationPolicyPublicId} is the structured policy the source quotation was priced under
     * (null for a direct booking, which falls back to the tenant company default as-of the booking
     * date). If the tenant somehow has no company default yet, the pin is left null and the cancel
     * flow resolves + pins it on first cancel — the pin is never silently skipped without a trace.
     */
    private void pinCancellationPolicy(Booking booking, UUID quotationPolicyPublicId, Long tenantId) {
        policyResolver.resolveForNewBooking(tenantId, quotationPolicyPublicId, booking.getBookingDate())
                .ifPresentOrElse(p -> {
                    booking.setCancellationPolicyPublicId(p.getPublicId());
                    booking.setCancellationPolicyVersion(p.getVersion());
                }, () -> log.warn("No cancellation policy resolved for booking {} (tenant {}); "
                        + "it will be resolved and pinned at cancel time", booking.getBookingCode(), tenantId));
    }

    /** Create/convert path: set the initial paidAmount, then compute totals + status. */
    private void calculateAndApplyFinancials(Booking booking,
                                             BigDecimal customerAmount,
                                             BigDecimal vendorCost,
                                             BigDecimal paidAmount) {
        booking.setPaidAmount(paidAmount);
        recomputeTotals(booking, customerAmount, vendorCost);
    }

    /**
     * Append a receipt to the payment ledger so {@code booking.paidAmount} and the itemised
     * {@code booking_payments} rows (the invoice's "Payments Received" table + {@code GET /payments})
     * stay reconciled. Does NOT mutate paidAmount — the caller already owns that. {@code tenantId} is
     * auto-stamped by {@code TenantEntityListener}.
     */
    private void recordPaymentLedgerRow(Booking booking, BigDecimal amount, String type,
                                        LocalDate date, String reference, String notes) {
        paymentRepository.save(BookingPayment.builder()
                .bookingId(booking.getId())
                .amount(amount)
                .paymentType(type)
                .paymentDate(date != null ? date : LocalDate.now())
                .reference(reference)
                .notes(notes)
                .build());
    }

    private PaymentStatus derivePaymentStatus(
            BigDecimal paidAmount, BigDecimal totalPayable, PaymentStatus current) {
        // REFUNDED is terminal — never let a totals recompute or a ledger op downgrade it.
        if (current == PaymentStatus.REFUNDED)          return PaymentStatus.REFUNDED;
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) return PaymentStatus.UNPAID;
        if (paidAmount.compareTo(totalPayable) >= 0)    return PaymentStatus.PAID;
        return PaymentStatus.PARTIAL;
    }
}