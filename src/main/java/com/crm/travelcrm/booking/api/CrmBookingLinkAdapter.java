package com.crm.travelcrm.booking.api;

import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingExpense;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
import com.crm.travelcrm.booking.service.BookingProfitService;
import com.crm.travelcrm.booking.service.BookingService;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one implementation of {@link CrmBookingLinkPort} — and the only place marketplace code can
 * reach booking internals.
 *
 * <p><b>{@code @Transactional} is per method, never on the class.</b> {@code TenantFilterAspect}
 * matches {@code @annotation(Transactional)}, so a class-level annotation would leave these methods
 * running with the tenant filter disabled. Every lookup here is tenant-parameterised anyway, which is
 * the belt to that suspenders: {@link #projectHotel} runs inside {@code TenantScope} on a SuperAdmin
 * thread, where trusting an ambient filter would be a cross-tenant read waiting to happen.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrmBookingLinkAdapter implements CrmBookingLinkPort {

    private static final Set<BookingStatus> TERMINAL = Set.of(
            BookingStatus.COMPLETED, BookingStatus.CANCELLED, BookingStatus.REFUNDED);

    private static final String HOTEL_SERVICE_TYPE = "Hotel";
    private static final String PLATFORM_PAYEE = "TravelCRM Marketplace";

    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final BookingExpenseRepository expenseRepository;
    private final BookingService bookingService;
    private final BookingProfitService profitService;
    private final SubAgentScope subAgentScope;
    private final Validator validator;

    // ── LINK ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CrmBookingRef requireLinkable(UUID bookingPublicId, Long tenantId) {
        Booking booking = bookingRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(bookingPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));

        // A sub-agent may only attach a hotel to a booking it owns. 404, never 403 — existence is
        // not something a foreign publicId should be able to probe.
        subAgentScope.assertVisible(booking, bookingPublicId);

        // The service-item layer has NO lifecycle guard of its own, so without this a hotel could be
        // attached to a booking that is already cancelled or refunded — and then never cancelled back,
        // because the cancellation that closed it has already frozen its P&L.
        if (booking.getStatus() != null && TERMINAL.contains(booking.getStatus())) {
            throw new BusinessException(
                    "Booking " + booking.getBookingCode() + " is " + booking.getStatus()
                            + " and can no longer take a new hotel.", HttpStatus.CONFLICT);
        }
        return toRef(booking);
    }

    /**
     * Display-only lookup. Deliberately does NOT apply the terminal-status guard: a cancelled trip
     * still has a booking code, and the marketplace row that rode on it still has to render it.
     *
     * <p>Sub-agent scoping still applies — a partner must not learn the code of a booking it cannot
     * see — but it degrades to {@code empty()} rather than throwing, because this feeds a read model.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CrmBookingRef> lookup(UUID bookingPublicId, Long tenantId) {
        if (bookingPublicId == null || tenantId == null) return Optional.empty();
        // Mirrors SubAgentScope.assertVisible, but as a predicate: a sub-agent sees only rows it owns
        // (an unowned row is invisible to it too), every other role is unrestricted.
        Long restrictTo = subAgentScope.ownerFilter();
        return bookingRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(bookingPublicId, tenantId)
                .filter(b -> restrictTo == null || restrictTo.equals(b.getOwnerUserId()))
                .map(CrmBookingLinkAdapter::toRef);
    }

    // ── CREATE ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CrmBookingRef createForMarketplace(MarketplaceBookingSeed seed) {
        // The duplicate guard lives ONLY in convertLeadToBooking; create() has none, yet it happily
        // accepts a leadPublicId and links it. Without this, marketplace-booking a hotel for a lead
        // that was already converted mints a SECOND booking on the same lead: two BKG codes, two
        // quota slots, two GST invoices possible — and if the owner is a sub-agent, commission
        // accrues twice on the same sale, because the ledger keys on booking id.
        if (seed.leadPublicId() != null) {
            bookingRepository.findFirstByLeadPublicIdActive(seed.leadPublicId(), seed.tenantId())
                    .ifPresent(existing -> {
                        throw new BusinessException(
                                "This enquiry is already booked as " + existing.getBookingCode()
                                        + ". Attach the hotel to that booking instead.",
                                HttpStatus.CONFLICT);
                    });
        }

        CreateBookingRequestDTO dto = new CreateBookingRequestDTO();
        dto.setCustomer(seed.customer());
        dto.setDestination(seed.destination());
        dto.setBookingDate(seed.bookingDate());
        dto.setTravelDate(seed.travelDate());
        dto.setCustomerAmount(scale(seed.customerSellingAmount()));
        // Required to be strictly positive by the DTO, and it is the honest figure at this instant.
        // It is immediately handed over to projectHotel — see the note below.
        dto.setVendorCost(scale(seed.tenantPayableAmount()));
        dto.setOverseasTourPackage(seed.overseasTourPackage());
        dto.setLeadPublicId(seed.leadPublicId());
        dto.setAssignedUserId(seed.assignedUserPublicId());
        dto.setServices(new ArrayList<>(List.of("Hotel")));

        // @Valid lives on the controller, so an in-process call bypasses bean validation entirely.
        // Run it here or a malformed seed reaches the entity and fails as a 500 at flush time.
        Set<ConstraintViolation<CreateBookingRequestDTO>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            throw new BusinessException(
                    "Cannot create a booking for this order: "
                            + violations.stream()
                                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                                    .collect(Collectors.joining("; ")),
                    HttpStatus.BAD_REQUEST);
        }

        BookingResponseDTO created = bookingService.create(dto);

        // Hand vendorCost over to the projection, which is its single writer from here on.
        //
        // `vendorCost` has exactly one meaning (ExpenseCostType: "already represents everything paid
        // to suppliers") and therefore may have exactly one writer. projectHotel recomputes it as
        // typedPortion + Σ(marketplace payables); if the payable were left in place here it would be
        // counted as a typed portion AND as a marketplace payable, doubling the cost on the very
        // first projection. Zeroing it makes typedPortion 0, which is the truth: nobody typed
        // anything on a booking the marketplace just minted.
        Booking booking = bookingRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(created.getPublicId(), seed.tenantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Booking vanished immediately after create: " + created.getPublicId()));
        booking.setVendorCost(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        bookingRepository.save(booking);
        // …and netProfit with it. create() stamped the margin from the seed's placeholder vendorCost
        // (MarketplaceBookingWriter hands over tenantPayableAmount = the selling amount), so without
        // this a marketplace-minted booking was persisted at netProfit 0.00 on a full-value sale and
        // stayed there until some unrelated expense edit happened to recompute it.
        profitService.apply(booking);

        log.info("Marketplace order {} minted booking {} for tenant {}",
                seed.marketplaceBookingPublicId(), booking.getBookingCode(), seed.tenantId());
        return toRef(booking);
    }

    // ── PROJECT (idempotent upsert) ─────────────────────────────────────────

    @Override
    @Transactional
    public CrmMarketplaceProjection projectHotel(MarketplaceHotelProjectionCommand cmd) {
        Booking booking = requireBooking(cmd.bookingPublicId(), cmd.tenantId());

        // Read the marketplace portion BEFORE the upsert. That single value is what makes
        // syncVendorCost stateless — no marker column on `bookings`, which matters because Booking is
        // @Audited and every column added there needs its twin in bookings_aud or EVERY write to
        // EVERY booking fails.
        BigDecimal marketplaceBefore = nz(expenseRepository.sumMarketplacePayable(booking.getId()));

        BookingServiceItem item = upsertServiceItem(booking, cmd);
        BookingExpense expense = upsertPayable(booking, cmd);
        syncVendorCost(booking, marketplaceBefore);

        return new CrmMarketplaceProjection(item.getPublicId(), expense.getPublicId());
    }

    private BookingServiceItem upsertServiceItem(Booking booking, MarketplaceHotelProjectionCommand cmd) {
        // Looks THROUGH the soft-delete: the partial unique index excludes deleted rows but they
        // still hold the key, so a `…AndDeletedAtIsNull` finder would miss one and the insert branch
        // would collide. Replays must un-delete, not re-create.
        BookingServiceItem item = serviceItemRepository
                .findByMarketplaceBookingPublicId(cmd.marketplaceBookingPublicId())
                .orElseGet(BookingServiceItem::new);

        if (item.getId() == 0L) {
            item.setTenantId(cmd.tenantId());
            item.setBookingId(booking.getId());
            item.setMarketplaceBookingPublicId(cmd.marketplaceBookingPublicId());
        }
        item.restore();   // no-op unless a previous withdrawal soft-deleted it

        item.setServiceType(HOTEL_SERVICE_TYPE);
        // NOT NULL on the entity — a null here is a 500 at flush, not a validation message.
        item.setTitle(cmd.title());
        item.setDescription(buildDescription(cmd));
        item.setServiceDate(cmd.checkIn());
        item.setEndDate(cmd.checkOut());
        item.setStatus(cmd.status() == null ? ServiceItemStatus.PENDING : cmd.status());
        item.setConfirmationNumber(cmd.confirmationNumber());
        item.setNotes(cmd.notes());

        // cost and vendorCost stay NULL, deliberately. Any service item with cost > 0 flips the GST
        // tax invoice from a single customerAmount line to per-item lines and bills ONLY the items
        // (InvoiceServiceImpl) — a priced hotel line would under-bill tax on the whole package.
        item.setCost(null);
        item.setVendorCost(null);

        // The platform can never be the line's Vendor: assignVendor resolves strictly against the
        // tenant's own vendor master. The payee name lives on the expense instead.
        item.setVendorId(null);
        item.setVendorPublicId(null);
        item.setVendorNameSnapshot(null);

        return serviceItemRepository.save(item);
    }

    private BookingExpense upsertPayable(Booking booking, MarketplaceHotelProjectionCommand cmd) {
        BookingExpense expense = expenseRepository
                .findByMarketplaceBookingPublicId(cmd.marketplaceBookingPublicId())
                .orElseGet(BookingExpense::new);

        boolean isNew = expense.getId() == 0L;
        if (isNew) {
            expense.setTenantId(cmd.tenantId());
            expense.setBookingId(booking.getId());
            expense.setMarketplaceBookingPublicId(cmd.marketplaceBookingPublicId());
            expense.setPaidAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            expense.setExpenseDate(cmd.checkIn());
        }
        expense.restore();

        expense.setCostType(ExpenseCostType.VENDOR);       // never INTERNAL: that means agency overhead
        expense.setCategory("Hotel");
        expense.setVendorName(PLATFORM_PAYEE);
        expense.setDescription(buildDescription(cmd));
        expense.setDueDate(cmd.payableDueDate());

        BigDecimal target = scale(cmd.tenantPayableAmount());
        BigDecimal paid = nz(expense.getPaidAmount());

        // NEVER restate below what has already been settled. The expense settlement calculator
        // rejects paid > amount outright (400), so a re-priced approval on a part-settled payable
        // would fail — and the compensating scheduler would replay that identical, deterministic
        // failure forever. Hold the floor at `paid` and record the overpayment instead of throwing.
        if (target.compareTo(paid) < 0) {
            log.warn("Marketplace payable {} restated to {} but {} already settled — holding at {} "
                            + "and flagging the {} credit due back.",
                    cmd.marketplaceBookingPublicId(), target, paid, paid, paid.subtract(target));
            expense.setNotes(appendNote(expense.getNotes(),
                    "Platform restated this payable to " + target + "; " + paid
                            + " was already settled, so " + paid.subtract(target)
                            + " is due back from the platform."));
            target = paid;
        }
        expense.setAmount(target);
        expense.setPaymentStatus(settlementOf(target, paid));

        return expenseRepository.save(expense);
    }

    /**
     * Keep {@code Booking.vendorCost} equal to typedPortion + every marketplace payable.
     *
     * <p>This is the whole reason the payable is not simply left in the expense ledger.
     * {@code ExpenseCostType} states that {@code vendorCost} "already represents everything paid to
     * suppliers", and VENDOR rows are excluded from {@code totalInternalCosts} precisely BECAUSE they
     * are assumed to be inside it. Break that and two things go wrong at once: {@code netProfit} is
     * overstated by the payable, and {@code CancellationCalculator} — whose only supplier term is
     * {@code sunkVendorCost} — freezes a wrong figure into the credit note permanently.</p>
     */
    private void syncVendorCost(Booking booking, BigDecimal marketplaceBefore) {
        BigDecimal marketplaceNow = nz(expenseRepository.sumMarketplacePayable(booking.getId()));

        // typedPortion is DERIVED, never stored: whatever the field held minus the marketplace
        // portion it held a moment ago. On a booking the marketplace itself minted that is zero
        // (createForMarketplace hands the field over deliberately); on a linked booking it is the
        // tenant's own typed cost, which is left exactly as they entered it.
        BigDecimal typedPortion = nz(booking.getVendorCost()).subtract(marketplaceBefore);
        if (typedPortion.signum() < 0) {
            typedPortion = BigDecimal.ZERO;
        }

        BigDecimal next = scale(typedPortion.add(marketplaceNow));
        if (next.compareTo(nz(booking.getVendorCost())) == 0) {
            return;   // idempotent: a replay of the same projection writes nothing
        }
        booking.setVendorCost(next);
        bookingRepository.save(booking);

        // netProfit = customerAmount − vendorCost − totalInternalCosts, and the cost term just moved.
        // Without this the method delivered only half of what the javadoc above promises: it kept the
        // payable out of totalInternalCosts (no double-count) but left the DENORMALISED margin at its
        // pre-projection figure, so approving a ₹40,000 hotel order overstated netProfit by ₹40,000 on
        // the dashboard hero card, the revenue report row + totals and the per-user leaderboard — until
        // some unrelated expense edit happened to call BookingProfitService and quietly corrected it.
        // The save above stays: apply() only writes when a figure moved, so on the (rare) edit where
        // customerAmount absorbs the change it would persist nothing and the new vendorCost would be lost.
        profitService.apply(booking);
    }

    // ── WITHDRAW ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void withdrawHotel(UUID marketplaceBookingPublicId, Long tenantId, String reason) {
        serviceItemRepository.findByMarketplaceBookingPublicId(marketplaceBookingPublicId)
                .ifPresent(item -> {
                    item.setStatus(ServiceItemStatus.CANCELLED);
                    item.setNotes(appendNote(item.getNotes(), reason));
                    serviceItemRepository.save(item);
                });

        expenseRepository.findByMarketplaceBookingPublicId(marketplaceBookingPublicId)
                .ifPresent(expense -> {
                    // Tenant-scoped by internal id, never findById: this runs under TenantScope from
                    // the SuperAdmin approval thread, where the Hibernate filter is not something to
                    // rely on — it fails OPEN on an empty context.
                    Booking booking = bookingRepository
                            .findByIdAndTenantIdAndDeletedAtIsNull(expense.getBookingId(), tenantId)
                            .orElse(null);
                    BigDecimal marketplaceBefore = booking == null
                            ? BigDecimal.ZERO
                            : nz(expenseRepository.sumMarketplacePayable(booking.getId()));

                    BigDecimal paid = nz(expense.getPaidAmount());
                    if (paid.signum() == 0) {
                        // Nothing moved — the payable never existed in substance.
                        expense.softDelete(null);
                    } else {
                        // Money HAS moved. Deleting a settled payable destroys the record of it, and
                        // the settlement calculator refuses amount <= 0 anyway. Keep the row, hold it
                        // at what was paid, and say what is owed back.
                        expense.setNotes(appendNote(expense.getNotes(),
                                "Order withdrawn" + (reason == null || reason.isBlank() ? "" : ": " + reason)
                                        + " — " + paid + " already settled and is due back from the platform."));
                        expense.setAmount(paid);
                        expense.setPaymentStatus(settlementOf(paid, paid));
                    }
                    expenseRepository.save(expense);

                    if (booking != null) {
                        syncVendorCost(booking, marketplaceBefore);
                    }
                });
    }

    // ── INVERSE: the CRM cancelled, tell the marketplace ────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UUID> marketplaceBookingsOn(Long bookingId) {
        return expenseRepository.findByBookingIdOrderByIdAsc(bookingId).stream()
                .map(BookingExpense::getMarketplaceBookingPublicId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    // ── internals ───────────────────────────────────────────────────────────

    private Booking requireBooking(UUID publicId, Long tenantId) {
        return bookingRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + publicId));
    }

    private static CrmBookingRef toRef(Booking b) {
        return new CrmBookingRef(b.getPublicId(), b.getBookingCode(),
                b.getCustomerPublicId(), b.getOwnerUserId());
    }

    private static String buildDescription(MarketplaceHotelProjectionCommand cmd) {
        StringBuilder sb = new StringBuilder(cmd.hotelName() == null ? "Hotel" : cmd.hotelName());
        if (cmd.cityName() != null && !cmd.cityName().isBlank()) sb.append(", ").append(cmd.cityName());
        if (cmd.roomName() != null && !cmd.roomName().isBlank()) sb.append(" · ").append(cmd.roomName());
        if (cmd.mealPlanName() != null && !cmd.mealPlanName().isBlank()) sb.append(" · ").append(cmd.mealPlanName());
        if (cmd.rooms() != null && cmd.rooms() > 1) sb.append(" · ").append(cmd.rooms()).append(" rooms");
        if (cmd.checkIn() != null && cmd.checkOut() != null) {
            sb.append(" · ").append(cmd.checkIn()).append(" → ").append(cmd.checkOut());
        }
        if (cmd.confirmationNumber() != null && !cmd.confirmationNumber().isBlank()) {
            sb.append(" · Conf ").append(cmd.confirmationNumber());
        }
        return sb.length() > 500 ? sb.substring(0, 500) : sb.toString();
    }

    private static ExpensePaymentStatus settlementOf(BigDecimal amount, BigDecimal paid) {
        if (paid.signum() <= 0) return ExpensePaymentStatus.CREDIT;
        return paid.compareTo(amount) >= 0 ? ExpensePaymentStatus.PAID : ExpensePaymentStatus.PARTIAL;
    }

    private static String appendNote(String existing, String addition) {
        if (addition == null || addition.isBlank()) return existing;
        return (existing == null || existing.isBlank()) ? addition : existing + "\n" + addition;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
