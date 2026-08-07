package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.BulkCreateBookingExpensesRequest;
import com.crm.travelcrm.booking.dto.request.CreateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.response.BookingExpenseResponse;
import com.crm.travelcrm.booking.dto.response.BookingExpenseSummaryResponse;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingExpense;
import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.service.ExpenseSettlementCalculator.Settlement;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Expense-ledger implementation.
 *
 * <p>Two invariants hold on every write, and they are the reason this class exists rather than a
 * generic CRUD service:
 * <ol>
 *   <li><b>The server settles the money.</b> {@code paidAmount}, the outstanding balance and
 *       {@code paymentStatus} all come out of {@link ExpenseSettlementCalculator}; the browser's
 *       own arithmetic — including the {@code outstandingAmount} it posts — is discarded.</li>
 *   <li><b>The parent booking gates everything.</b> Every public method resolves the booking
 *       through {@link #findActiveBooking(UUID)}, which applies {@code SubAgentScope}. Expense
 *       rows carry no owner of their own; they inherit the booking's, so a lookup that skipped
 *       that helper would be an IDOR — a sub-agent holds {@code BOOKING_READ}/{@code BOOKING_UPDATE}
 *       by default and shares the tenant, so neither {@code @PreAuthorize} nor the Hibernate
 *       tenant filter would stop it.</li>
 * </ol>
 *
 * <p>{@code @Transactional} is on each METHOD, never the class: {@code TenantFilterAspect}'s
 * pointcut matches method-level only, so a class-level annotation would silently leave every query
 * here unfiltered across tenants (and {@code LeadSourceAdapterPurityArchTest} fails the build on
 * it).
 */
@Service
@RequiredArgsConstructor
public class BookingExpenseServiceImpl implements BookingExpenseService {

    private static final Logger log = LogManager.getLogger(BookingExpenseServiceImpl.class);

    private final BookingRepository        bookingRepository;
    private final BookingExpenseRepository expenseRepository;
    private final SubAgentScope            subAgentScope;
    private final BookingProfitService     profitService;

    @Override
    @Transactional(readOnly = true)
    public List<BookingExpenseResponse> getExpenses(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        LocalDate today = LocalDate.now();

        // Row-level split of the two authorities that can reach this endpoint.
        //
        // BOOKING_PROFIT_READ sees the whole ledger. MARKETPLACE_PAYABLE_READ sees ONLY the rows
        // carrying a marketplace link — because a marketplace payable is a *price the tenant must
        // know to quote its customer*, not a margin. TRAVEL_AGENT holds the latter and not the
        // former, and without this split it can place a platform order and then not see what the
        // order costs, which makes the feature unusable rather than merely restricted.
        //
        // Enforced here rather than at the controller because @PreAuthorize gates the call, not the
        // rows it returns.
        boolean fullLedger = hasAuthority("BOOKING_PROFIT_READ");

        return expenseRepository
                .findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(booking.getId())
                .stream()
                .filter(expense -> fullLedger || expense.getMarketplaceBookingPublicId() != null)
                .map(expense -> toResponse(expense, today))
                .toList();
    }

    /** Authority probe against the current principal. Mirrors BookingTimelineServiceImpl:178. */
    private static boolean hasAuthority(String authority) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    @Override
    @Transactional
    public List<BookingExpenseResponse> addExpenses(UUID bookingPublicId,
                                                    BulkCreateBookingExpensesRequest request) {
        Booking booking = findActiveBooking(bookingPublicId);
        LocalDate today = LocalDate.now();

        // Built in a loop rather than a stream so the row index is available for the error message:
        // with several rows in one submission, "Expense #3" is the difference between a fixable
        // complaint and an unusable one.
        List<BookingExpenseResponse> created = new ArrayList<>(request.getExpenses().size());
        int rowNumber = 0;

        for (CreateBookingExpenseRequest row : request.getExpenses()) {
            rowNumber++;
            validateDates(row.getExpenseDate(), row.getDueDate(), rowNumber);

            Settlement settlement = settle(row.getAmount(), row.getPaidAmount(),
                    row.getPaymentStatus(), rowNumber);

            BookingExpense expense = BookingExpense.builder()
                    .bookingId(booking.getId())
                    // Category is a display label only; an internal-cost client need not send one.
                    .category(StringUtils.hasText(row.getCategory()) ? row.getCategory() : "Other")
                    // Absent ⇒ VENDOR: the safe default. An unclassified line stays out of the
                    // margin rather than silently reducing it — over-stating cost on every existing
                    // booking would be the worse failure.
                    .costType(row.getCostType() != null ? row.getCostType() : ExpenseCostType.VENDOR)
                    .description(row.getDescription())
                    .vendorName(row.getVendorName())
                    .amount(row.getAmount())
                    .paidAmount(settlement.paidAmount())
                    .paymentStatus(settlement.status())
                    .paymentMode(row.getPaymentMode())
                    .expenseDate(row.getExpenseDate())
                    .dueDate(row.getDueDate())
                    .referenceNumber(row.getReferenceNumber())
                    .notes(row.getNotes())
                    .build();

            created.add(toResponse(expenseRepository.save(expense), today));
        }

        // One recalculation for the whole batch, after every row is written — not one per row.
        // apply() recomputes from source rather than accumulating, so the single call is complete.
        profitService.apply(booking);

        log.info("{} expense line(s) recorded on booking {}", created.size(), booking.getBookingCode());
        return created;
    }

    @Override
    @Transactional
    public BookingExpenseResponse updateExpense(UUID bookingPublicId, UUID expensePublicId,
                                                UpdateBookingExpenseRequest request) {
        Booking booking = findActiveBooking(bookingPublicId);
        BookingExpense expense = findExpense(booking, expensePublicId);
        assertNotMarketplaceOwned(expense, "edited");

        if (request.getCategory() != null)        expense.setCategory(request.getCategory());
        if (request.getCostType() != null)        expense.setCostType(request.getCostType());
        if (request.getDescription() != null)     expense.setDescription(request.getDescription());
        if (request.getVendorName() != null)      expense.setVendorName(request.getVendorName());
        if (request.getPaymentMode() != null)     expense.setPaymentMode(request.getPaymentMode());
        if (request.getExpenseDate() != null)     expense.setExpenseDate(request.getExpenseDate());
        if (request.getDueDate() != null)         expense.setDueDate(request.getDueDate());
        if (request.getReferenceNumber() != null) expense.setReferenceNumber(request.getReferenceNumber());
        if (request.getNotes() != null)           expense.setNotes(request.getNotes());

        validateDates(expense.getExpenseDate(), expense.getDueDate(), 0);

        // The money triple is merged over the row's CURRENT values and re-settled as a unit, so a
        // request that touches one of the three still lands on a consistent set of all three.
        Settlement settlement = settle(
                request.getAmount()     != null ? request.getAmount()     : expense.getAmount(),
                request.getPaidAmount() != null ? request.getPaidAmount() : expense.getPaidAmount(),
                mergeIntent(request, expense),
                0);

        if (request.getAmount() != null) expense.setAmount(request.getAmount());
        expense.setPaidAmount(settlement.paidAmount());
        expense.setPaymentStatus(settlement.status());

        BookingExpense saved = expenseRepository.save(expense);
        // Covers all three ways an edit can move the margin: the amount changed, or the line was
        // reclassified into INTERNAL, or out of it.
        profitService.apply(booking);

        log.info("Expense {} updated on booking {} -> paid {}/{} [{}] as {}",
                expensePublicId, booking.getBookingCode(),
                saved.getPaidAmount(), saved.getAmount(), saved.getPaymentStatus(), saved.getCostType());
        return toResponse(saved, LocalDate.now());
    }

    @Override
    @Transactional
    public void deleteExpense(UUID bookingPublicId, UUID expensePublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        BookingExpense expense = findExpense(booking, expensePublicId);
        assertNotMarketplaceOwned(expense, "deleted");

        expense.softDelete(currentUserEmail());
        expenseRepository.save(expense);
        profitService.apply(booking);

        log.info("Expense {} removed from booking {}", expensePublicId, booking.getBookingCode());
    }

    @Override
    @Transactional
    public BookingExpenseResponse restoreExpense(UUID bookingPublicId, UUID expensePublicId) {
        Booking booking = findActiveBooking(bookingPublicId);

        // The one lookup that must see deleted rows — restore's target is deleted by definition.
        // Still booking-scoped, so it can only ever reach a trashed line on a booking the caller
        // already passed SubAgentScope for.
        BookingExpense expense = expenseRepository
                .findByPublicIdAndBookingId(expensePublicId, booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense not found on this booking: " + expensePublicId));

        if (!expense.isDeleted()) {
            throw new BusinessException("This expense is not deleted.", HttpStatus.CONFLICT);
        }
        assertNotMarketplaceOwned(expense, "restored");

        expense.restore();
        BookingExpense saved = expenseRepository.save(expense);
        // An INTERNAL row coming back re-enters the margin — the mirror of deleteExpense.
        profitService.apply(booking);

        log.info("Expense {} restored on booking {}", expensePublicId, booking.getBookingCode());
        return toResponse(saved, LocalDate.now());
    }

    /**
     * A marketplace payable row belongs to the platform, not the tenant — it is written and revised
     * only by {@code CrmBookingLinkAdapter}, and {@code Booking.vendorCost} is kept in step with it
     * there. Letting this ledger mutate the row breaks that pairing in three different directions:
     *
     * <ul>
     *   <li><b>Reclassify to INTERNAL</b> — the same rupees are then inside {@code vendorCost} AND
     *       {@code totalInternalCosts}, so {@code netProfit} is under-stated by the payable twice
     *       over. (The {@code costType = VENDOR} predicate on {@code sumMarketplacePayable} now also
     *       closes this from the query side.)</li>
     *   <li><b>Delete / restore</b> — {@code syncVendorCost} derives the tenant's own cost as
     *       {@code vendorCost − marketplaceBefore}. Soft-deleting the row drops it out of that sum
     *       while {@code vendorCost} still contains it, so the very next platform revision reads a
     *       {@code typedPortion} inflated by the whole payable and stores it FOREVER: a ₹5,000
     *       payable deleted here, then revised to ₹6,000, left {@code vendorCost} at ₹11,000 on a
     *       booking where the tenant typed nothing.</li>
     *   <li><b>Edit the amount</b> — same mis-split, one revision later.</li>
     * </ul>
     *
     * <p>Corrections belong on the marketplace order, which re-projects here. Refusing is the only
     * answer that keeps one owner per figure.
     */
    private static void assertNotMarketplaceOwned(BookingExpense expense, String verb) {
        if (expense.getMarketplaceBookingPublicId() != null) {
            throw new BusinessException(
                    "This line is a hotel-marketplace payable and cannot be " + verb
                            + " here — change it on the marketplace order instead.",
                    HttpStatus.CONFLICT);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BookingExpenseSummaryResponse getSummary(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        LocalDate today = LocalDate.now();

        List<BookingExpense> expenses = expenseRepository
                .findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(booking.getId());

        // Folded in Java rather than aggregated in SQL: a booking carries a handful of expense
        // lines, and the overdue split depends on isUnsettled()/getOutstandingAmount(), which are
        // @Transient. Reproducing that logic in JPQL would give the totals a second, drift-prone
        // definition.
        BigDecimal totalExpense       = BigDecimal.ZERO;
        BigDecimal totalVendorExpense = BigDecimal.ZERO;
        BigDecimal totalInternalCosts = BigDecimal.ZERO;
        BigDecimal totalPaid          = BigDecimal.ZERO;
        BigDecimal totalOutstanding   = BigDecimal.ZERO;
        BigDecimal overdueOutstanding = BigDecimal.ZERO;
        long unsettledCount = 0;
        long overdueCount   = 0;

        for (BookingExpense expense : expenses) {
            BigDecimal outstanding = expense.getOutstandingAmount();
            totalExpense     = totalExpense.add(nz(expense.getAmount()));
            totalPaid        = totalPaid.add(nz(expense.getPaidAmount()));
            totalOutstanding = totalOutstanding.add(outstanding);

            // Same split the profit recalc uses, over the same active-row set — so this summary and
            // Booking.totalInternalCosts are two views of one number, not two definitions of it.
            if (expense.getCostType() == ExpenseCostType.INTERNAL) {
                totalInternalCosts = totalInternalCosts.add(nz(expense.getAmount()));
            } else {
                totalVendorExpense = totalVendorExpense.add(nz(expense.getAmount()));
            }

            if (expense.isUnsettled()) {
                unsettledCount++;
                if (isOverdue(expense, today)) {
                    overdueCount++;
                    overdueOutstanding = overdueOutstanding.add(outstanding);
                }
            }
        }

        return BookingExpenseSummaryResponse.builder()
                .totalExpense(totalExpense)
                .totalVendorExpense(totalVendorExpense)
                .totalInternalCosts(totalInternalCosts)
                .totalPaid(totalPaid)
                .totalOutstanding(totalOutstanding)
                .overdueOutstanding(overdueOutstanding)
                .expenseCount(expenses.size())
                .unsettledCount(unsettledCount)
                .overdueCount(overdueCount)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The one place a booking is resolved. Every public method goes through it so the sub-agent
     * ownership check can never be skipped on one code path — the pattern both sibling services
     * use.
     */
    private Booking findActiveBooking(UUID bookingPublicId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
        subAgentScope.assertVisible(booking, bookingPublicId);
        return booking;
    }

    /** Scoped to the already-authorised booking, so an expense publicId from another booking 404s. */
    private BookingExpense findExpense(Booking booking, UUID expensePublicId) {
        return expenseRepository
                .findByPublicIdAndBookingIdAndDeletedAtIsNull(expensePublicId, booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Expense not found on this booking: " + expensePublicId));
    }

    /**
     * Which settlement intent a patch request carries — the subtlest rule in this class, because
     * {@code paymentStatus} is BOTH an input the caller may state and an output the server derives.
     * Feeding the stored (derived) status back in as intent would let a value the server computed
     * outrank a figure the caller explicitly typed. Precedence, strongest first:
     *
     * <ol>
     *   <li><b>An explicit {@code paymentStatus}</b> — the caller said what they mean. "Mark this
     *       credit line settled" is {@code {"paymentStatus":"PAID"}} and fills the paid amount.</li>
     *   <li><b>An explicit {@code paidAmount}</b> ⇒ {@code PARTIAL}, i.e. "take my figure at face
     *       value". Without this rule a paid-amount-only patch is silently discarded on any CREDIT
     *       row (forced back to 0) or PAID row (forced back to the full amount) — and, because the
     *       status is then re-derived from that forced figure, it would return 200 with the
     *       untouched row. Recording a part-payment against an udhar line is the endpoint's whole
     *       purpose, so it must not require the caller to also know the status vocabulary. The
     *       derived status still comes out of the money: 0 ⇒ CREDIT, full ⇒ PAID.</li>
     *   <li><b>Neither</b> — an amount-only edit. The stored status is the right fallback here and
     *       only here: raising the amount on a row that was settled in full should keep it settled,
     *       not silently open a balance. (Lowering it below what was already disbursed is rejected
     *       by the calculator rather than clamped.)</li>
     * </ol>
     */
    private static ExpensePaymentStatus mergeIntent(UpdateBookingExpenseRequest request,
                                                    BookingExpense expense) {
        if (request.getPaymentStatus() != null) return request.getPaymentStatus();
        if (request.getPaidAmount() != null)    return ExpensePaymentStatus.PARTIAL;
        return expense.getPaymentStatus();
    }

    /**
     * @param rowNumber 1-based position in a bulk submission, or 0 for a single-row operation
     *                  (where a position would be noise)
     */
    private static void validateDates(LocalDate expenseDate, LocalDate dueDate, int rowNumber) {
        if (dueDate != null && expenseDate != null && dueDate.isBefore(expenseDate)) {
            throw new BusinessException(
                    rowPrefix(rowNumber) + "Payment due date cannot be before the expense date.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** Wraps the calculator so a bulk row's position survives into the message. */
    private static Settlement settle(BigDecimal amount, BigDecimal paidAmount,
                                     ExpensePaymentStatus status, int rowNumber) {
        try {
            return ExpenseSettlementCalculator.settle(amount, paidAmount, status);
        } catch (BusinessException e) {
            if (rowNumber <= 0) throw e;
            throw new BusinessException(rowPrefix(rowNumber) + e.getMessage(), e.getStatus());
        }
    }

    private static String rowPrefix(int rowNumber) {
        return rowNumber > 0 ? "Expense #" + rowNumber + ": " : "";
    }

    private static boolean isOverdue(BookingExpense expense, LocalDate today) {
        return expense.getDueDate() != null
                && expense.getDueDate().isBefore(today)
                && expense.isUnsettled();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String currentUserEmail() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private static BookingExpenseResponse toResponse(BookingExpense e, LocalDate today) {
        return BookingExpenseResponse.builder()
                .publicId(e.getPublicId())
                .category(e.getCategory())
                .costType(e.getCostType())
                .description(e.getDescription())
                .vendorName(e.getVendorName())
                .amount(e.getAmount())
                .paidAmount(e.getPaidAmount())
                .outstandingAmount(e.getOutstandingAmount())
                .paymentStatus(e.getPaymentStatus())
                .paymentMode(e.getPaymentMode())
                .expenseDate(e.getExpenseDate())
                .dueDate(e.getDueDate())
                .overdue(isOverdue(e, today))
                .referenceNumber(e.getReferenceNumber())
                .notes(e.getNotes())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
