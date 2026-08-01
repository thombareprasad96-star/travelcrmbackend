package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.BulkCreateBookingExpensesRequest;
import com.crm.travelcrm.booking.dto.request.CreateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.response.BookingExpenseResponse;
import com.crm.travelcrm.booking.dto.response.BookingExpenseSummaryResponse;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingExpense;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for the expense ledger, in the booking module's established style: plain
 * JUnit 5 with hand-rolled Mockito mocks and no Spring context.
 *
 * <p>These exist because {@code ExpenseSettlementCalculatorTest} cannot catch the class of bug that
 * actually shipped here. The calculator was correct in isolation; the defect was in what the
 * SERVICE chose to hand it — a patch carrying only a {@code paidAmount} was given the row's stored
 * status as "intent", which then overrode the very figure being merged. Every rule about what the
 * caller meant lives in {@code mergeIntent}, so it needs coverage through the real update path.
 */
class BookingExpenseServiceImplTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID EXPENSE_ID = UUID.randomUUID();
    private static final long BOOKING_PK = 42L;

    private BookingRepository        bookingRepository;
    private BookingExpenseRepository expenseRepository;
    private SubAgentScope            subAgentScope;
    private BookingProfitService     profitService;
    private BookingExpenseServiceImpl service;

    private Booking booking;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        expenseRepository = mock(BookingExpenseRepository.class);
        subAgentScope     = mock(SubAgentScope.class);
        // Real, not mocked: profit recalculation now runs on every expense write, and these tests
        // are the only place that can catch it double-counting or firing on the wrong row set.
        profitService     = new BookingProfitService(
                bookingRepository, expenseRepository, mock(BookingCancellationRepository.class));
        service = new BookingExpenseServiceImpl(
                bookingRepository, expenseRepository, subAgentScope, profitService);

        booking = new Booking();
        booking.setId(BOOKING_PK);
        booking.setBookingCode("BKG-26-0001");

        when(bookingRepository.findByPublicIdAndDeletedAtIsNull(BOOKING_ID))
                .thenReturn(Optional.of(booking));
        when(expenseRepository.save(any(BookingExpense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A stored row with the given money position, already on the booking. */
    private BookingExpense storedExpense(String amount, String paid, ExpensePaymentStatus status) {
        BookingExpense expense = BookingExpense.builder()
                .bookingId(BOOKING_PK)
                .category("Hotel")
                .description("Hotel payment for 3 nights")
                .amount(new BigDecimal(amount))
                .paidAmount(new BigDecimal(paid))
                .paymentStatus(status)
                .expenseDate(LocalDate.of(2026, 7, 1))
                .build();
        when(expenseRepository.findByPublicIdAndBookingIdAndDeletedAtIsNull(EXPENSE_ID, BOOKING_PK))
                .thenReturn(Optional.of(expense));
        return expense;
    }

    private static CreateBookingExpenseRequest newRow(String amount, String paid,
                                                      ExpensePaymentStatus status) {
        CreateBookingExpenseRequest row = new CreateBookingExpenseRequest();
        row.setCategory("Hotel");
        row.setDescription("Hotel payment for 3 nights");
        row.setAmount(new BigDecimal(amount));
        row.setPaidAmount(paid == null ? null : new BigDecimal(paid));
        row.setPaymentStatus(status);
        row.setExpenseDate(LocalDate.of(2026, 7, 1));
        return row;
    }

    private static BulkCreateBookingExpensesRequest bulk(CreateBookingExpenseRequest... rows) {
        BulkCreateBookingExpensesRequest request = new BulkCreateBookingExpensesRequest();
        request.setExpenses(List.of(rows));
        return request;
    }

    @Nested
    @DisplayName("updateExpense — recording a disbursement never needs the status restated")
    class PatchIntent {

        @Test
        @DisplayName("REGRESSION: paidAmount alone settles part of a CREDIT line")
        void paidAmountAloneOnCreditRow() {
            // The bug: intent fell back to the row's stored CREDIT, which forced paid back to 0 and
            // returned 200 "Expense updated successfully" with the payment silently dropped.
            storedExpense("10000.00", "0.00", ExpensePaymentStatus.CREDIT);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setPaidAmount(new BigDecimal("4000.00"));

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getPaidAmount()).isEqualByComparingTo("4000.00");
            assertThat(response.getOutstandingAmount()).isEqualByComparingTo("6000.00");
            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PARTIAL);
        }

        @Test
        @DisplayName("paying the balance off in one go derives PAID without saying so")
        void paidAmountAloneClearingTheLine() {
            storedExpense("10000.00", "4000.00", ExpensePaymentStatus.PARTIAL);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setPaidAmount(new BigDecimal("10000.00"));

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PAID);
            assertThat(response.getOutstandingAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("correcting an over-recorded payment on a PAID line is honoured, not reverted")
        void paidAmountAloneOnPaidRow() {
            storedExpense("10000.00", "10000.00", ExpensePaymentStatus.PAID);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setPaidAmount(new BigDecimal("6000.00"));

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getPaidAmount()).isEqualByComparingTo("6000.00");
            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PARTIAL);
        }

        @Test
        @DisplayName("an explicit status still wins — PAID settles the line in full")
        void explicitStatusOutranksEverything() {
            storedExpense("10000.00", "0.00", ExpensePaymentStatus.CREDIT);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setPaymentStatus(ExpensePaymentStatus.PAID);

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getPaidAmount()).isEqualByComparingTo("10000.00");
            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PAID);
        }
    }

    @Nested
    @DisplayName("updateExpense — an amount-only edit preserves the settlement posture")
    class AmountOnlyEdits {

        @Test
        @DisplayName("raising the amount on a settled line keeps it settled in full")
        void raisingAmountOnPaidRow() {
            // This is why the stored status is still the fallback when no money figure is supplied:
            // the vendor's revised bill is higher, and the line was paid in full — it stays that way
            // rather than silently opening a ₹2,000 balance.
            storedExpense("10000.00", "10000.00", ExpensePaymentStatus.PAID);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setAmount(new BigDecimal("12000.00"));

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getAmount()).isEqualByComparingTo("12000.00");
            assertThat(response.getPaidAmount()).isEqualByComparingTo("12000.00");
            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PAID);
        }

        @Test
        @DisplayName("raising the amount on a part-paid line widens the balance, paid untouched")
        void raisingAmountOnPartialRow() {
            storedExpense("10000.00", "4000.00", ExpensePaymentStatus.PARTIAL);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setAmount(new BigDecimal("12000.00"));

            BookingExpenseResponse response = service.updateExpense(BOOKING_ID, EXPENSE_ID, request);

            assertThat(response.getPaidAmount()).isEqualByComparingTo("4000.00");
            assertThat(response.getOutstandingAmount()).isEqualByComparingTo("8000.00");
            assertThat(response.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.PARTIAL);
        }

        @Test
        @DisplayName("dropping the amount below what was already disbursed is refused, not clamped")
        void loweringAmountBelowPaidIsRejected() {
            storedExpense("10000.00", "4000.00", ExpensePaymentStatus.PARTIAL);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setAmount(new BigDecimal("3000.00"));

            assertThatThrownBy(() -> service.updateExpense(BOOKING_ID, EXPENSE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot exceed the expense amount");
            verify(expenseRepository, never()).save(any());
        }

        @Test
        @DisplayName("a due date before the expense date is refused")
        void dueDateBeforeExpenseDateIsRejected() {
            storedExpense("10000.00", "0.00", ExpensePaymentStatus.CREDIT);

            UpdateBookingExpenseRequest request = new UpdateBookingExpenseRequest();
            request.setDueDate(LocalDate.of(2026, 6, 1));   // expenseDate is 2026-07-01

            assertThatThrownBy(() -> service.updateExpense(BOOKING_ID, EXPENSE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("due date cannot be before");
            verify(expenseRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("addExpenses — the browser's arithmetic is not trusted")
    class BulkCreate {

        @Test
        @DisplayName("a CREDIT row is stored fully payable even if a paid amount was posted")
        void creditRowIgnoresPostedPaidAmount() {
            List<BookingExpenseResponse> created = service.addExpenses(BOOKING_ID,
                    bulk(newRow("5000.00", "2000.00", ExpensePaymentStatus.CREDIT)));

            assertThat(created).singleElement().satisfies(row -> {
                assertThat(row.getPaidAmount()).isEqualByComparingTo("0.00");
                assertThat(row.getOutstandingAmount()).isEqualByComparingTo("5000.00");
                assertThat(row.getPaymentStatus()).isEqualTo(ExpensePaymentStatus.CREDIT);
            });
        }

        @Test
        @DisplayName("every row in the batch is saved, in submission order")
        void allRowsAreSaved() {
            List<BookingExpenseResponse> created = service.addExpenses(BOOKING_ID, bulk(
                    newRow("5000.00", null, ExpensePaymentStatus.CREDIT),
                    newRow("3000.00", "3000.00", ExpensePaymentStatus.PAID),
                    newRow("2000.00", "500.00", ExpensePaymentStatus.PARTIAL)));

            assertThat(created).hasSize(3);
            assertThat(created).extracting(BookingExpenseResponse::getPaymentStatus)
                    .containsExactly(ExpensePaymentStatus.CREDIT,
                            ExpensePaymentStatus.PAID,
                            ExpensePaymentStatus.PARTIAL);
        }

        @Test
        @DisplayName("a bad row names its position, so the user knows which one to fix")
        void badRowIsIdentifiedByPosition() {
            assertThatThrownBy(() -> service.addExpenses(BOOKING_ID, bulk(
                    newRow("5000.00", null, ExpensePaymentStatus.CREDIT),
                    newRow("2000.00", "9999.00", ExpensePaymentStatus.PARTIAL))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageStartingWith("Expense #2:");
        }
    }

    @Nested
    @DisplayName("every operation is gated by the parent booking")
    class Scoping {

        @Test
        @DisplayName("an unknown or soft-deleted booking is a 404 before anything else happens")
        void unknownBookingIs404() {
            UUID unknown = UUID.randomUUID();
            when(bookingRepository.findByPublicIdAndDeletedAtIsNull(unknown))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExpenses(unknown))
                    .isInstanceOf(BookingNotFoundException.class);
        }

        @Test
        @DisplayName("the sub-agent ownership guard runs on reads, writes and the summary alike")
        void subAgentGuardRunsEverywhere() {
            storedExpense("1000.00", "0.00", ExpensePaymentStatus.CREDIT);
            when(expenseRepository.findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(BOOKING_PK))
                    .thenReturn(List.of());

            service.getExpenses(BOOKING_ID);
            service.getSummary(BOOKING_ID);
            service.addExpenses(BOOKING_ID, bulk(newRow("100.00", null, ExpensePaymentStatus.CREDIT)));
            service.updateExpense(BOOKING_ID, EXPENSE_ID, new UpdateBookingExpenseRequest());
            service.deleteExpense(BOOKING_ID, EXPENSE_ID);

            // Five operations, five ownership checks — a path that skipped the guarded helper would
            // be an IDOR, since a sub-agent holds BOOKING_READ/BOOKING_UPDATE and shares the tenant.
            verify(subAgentScope, org.mockito.Mockito.times(5))
                    .assertVisible(eq(booking), eq(BOOKING_ID));
        }

        @Test
        @DisplayName("an expense publicId from another booking is a 404, not someone else's row")
        void foreignExpenseIs404() {
            UUID foreign = UUID.randomUUID();
            when(expenseRepository.findByPublicIdAndBookingIdAndDeletedAtIsNull(foreign, BOOKING_PK))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteExpense(BOOKING_ID, foreign))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Expense not found on this booking");
        }
    }

    @Nested
    @DisplayName("the summary reports the ledger it summarises")
    class Summary {

        @Test
        @DisplayName("totals, unsettled and overdue are all derived from the live rows")
        void totalsAndOverdueSplit() {
            LocalDate past = LocalDate.now().minusDays(10);
            LocalDate future = LocalDate.now().plusDays(10);

            BookingExpense settled = BookingExpense.builder()
                    .bookingId(BOOKING_PK).amount(new BigDecimal("3000.00"))
                    .paidAmount(new BigDecimal("3000.00")).paymentStatus(ExpensePaymentStatus.PAID)
                    .expenseDate(past).build();
            BookingExpense overdue = BookingExpense.builder()
                    .bookingId(BOOKING_PK).amount(new BigDecimal("5000.00"))
                    .paidAmount(new BigDecimal("1000.00")).paymentStatus(ExpensePaymentStatus.PARTIAL)
                    .expenseDate(past).dueDate(past).build();
            BookingExpense notYetDue = BookingExpense.builder()
                    .bookingId(BOOKING_PK).amount(new BigDecimal("2000.00"))
                    .paidAmount(BigDecimal.ZERO).paymentStatus(ExpensePaymentStatus.CREDIT)
                    .expenseDate(past).dueDate(future).build();

            when(expenseRepository.findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(BOOKING_PK))
                    .thenReturn(List.of(settled, overdue, notYetDue));

            BookingExpenseSummaryResponse summary = service.getSummary(BOOKING_ID);

            assertThat(summary.getTotalExpense()).isEqualByComparingTo("10000.00");
            assertThat(summary.getTotalPaid()).isEqualByComparingTo("4000.00");
            assertThat(summary.getTotalOutstanding()).isEqualByComparingTo("6000.00");
            assertThat(summary.getOverdueOutstanding()).isEqualByComparingTo("4000.00");
            assertThat(summary.getExpenseCount()).isEqualTo(3);
            assertThat(summary.getUnsettledCount()).isEqualTo(2);
            assertThat(summary.getOverdueCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty ledger reports zeros, not nulls")
        void emptyLedgerIsAllZeros() {
            when(expenseRepository.findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(BOOKING_PK))
                    .thenReturn(List.of());

            BookingExpenseSummaryResponse summary = service.getSummary(BOOKING_ID);

            assertThat(summary.getTotalExpense()).isEqualByComparingTo("0");
            assertThat(summary.getTotalOutstanding()).isEqualByComparingTo("0");
            assertThat(summary.getExpenseCount()).isZero();
            assertThat(summary.getOverdueCount()).isZero();
        }
    }
}
