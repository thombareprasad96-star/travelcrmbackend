package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the one place a booking's profit is computed.
 *
 * <p>Two properties are load-bearing and neither is obvious from reading the formula:
 * <ol>
 *   <li><b>Idempotence.</b> {@code apply} is called from six different events and recomputes from
 *       source every time. If it accumulated a delta instead, a double-fire would silently corrupt
 *       the margin — so the fixture deliberately calls it twice in several places.</li>
 *   <li><b>The no-op guard.</b> {@code Booking} is {@code @Audited} and carries a {@code @Version}
 *       column, so a save that changed nothing would still mint an Envers revision and turn two
 *       harmless concurrent edits into a 409. The guard must use {@code compareTo}, not
 *       {@code equals} — {@code BigDecimal.equals} is scale-sensitive, so {@code 0} vs {@code 0.00}
 *       would read as a change and dirty the row on every single call.</li>
 * </ol>
 *
 * <p>Written in the booking module's established style: plain JUnit 5, hand-rolled Mockito mocks,
 * no Spring context.
 */
class BookingProfitServiceTest {

    private static final long BOOKING_PK = 42L;

    private BookingRepository             bookingRepository;
    private BookingExpenseRepository      expenseRepository;
    private BookingCancellationRepository cancellationRepository;
    private BookingProfitService          service;

    @BeforeEach
    void setUp() {
        bookingRepository      = mock(BookingRepository.class);
        expenseRepository      = mock(BookingExpenseRepository.class);
        cancellationRepository = mock(BookingCancellationRepository.class);
        service = new BookingProfitService(bookingRepository, expenseRepository, cancellationRepository);

        // Default: not cancelled. Individual tests opt in.
        when(cancellationRepository.findByBookingIdAndDeletedAtIsNull(BOOKING_PK))
                .thenReturn(Optional.empty());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private Booking booking(String customerAmount, String vendorCost) {
        Booking b = new Booking();
        b.setId(BOOKING_PK);
        b.setBookingCode("BKG-26-0001");
        b.setCustomerAmount(new BigDecimal(customerAmount));
        b.setVendorCost(new BigDecimal(vendorCost));
        b.setTotalInternalCosts(BigDecimal.ZERO);
        b.setTotalVendorCosts(BigDecimal.ZERO);
        b.setNetProfit(BigDecimal.ZERO);
        return b;
    }

    /** What the INTERNAL-only ledger sum returns for this booking. */
    private void internalCostsAre(String amount) {
        when(expenseRepository.sumInternalCosts(BOOKING_PK)).thenReturn(new BigDecimal(amount));
    }

    /** What the VENDOR-only ledger sum returns (marketplace payables already excluded in SQL). */
    private void vendorCostsAre(String amount) {
        when(expenseRepository.sumVendorCosts(BOOKING_PK)).thenReturn(new BigDecimal(amount));
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Nested
    @DisplayName("apply — the formula")
    class Formula {

        @Test
        @DisplayName("netProfit = customerAmount − vendorCost − totalVendorCosts − totalInternalCosts")
        void subtractsEveryCostTerm() {
            Booking b = booking("100000.00", "20000.00");
            vendorCostsAre("50000.00");
            internalCostsAre("8000.00");

            assertThat(service.apply(b)).isTrue();

            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(bd("50000.00"));
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(bd("8000.00"));
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("22000.00"));
            verify(bookingRepository).save(b);
        }

        @Test
        @DisplayName("REGRESSION: an itemised ledger with the vendorCost field left blank still reduces profit")
        void ledgerAloneDrivesTheMargin() {
            // The defect this formula was changed to fix. `Vendor Cost` is OPTIONAL on the booking
            // form and agencies itemise through the expense ledger instead, where every row defaults
            // to VENDOR. Those rows fed nothing, so a ₹1,00,000 booking carrying ₹75,940 of real
            // supplier spend reported the ENTIRE ₹1,00,000 as profit.
            Booking b = booking("100000.00", "0");
            vendorCostsAre("75940.00");
            internalCostsAre("0");

            service.apply(b);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("24060.00"));
        }

        @Test
        @DisplayName("the typed field and the ledger are ADDITIVE, not two spellings of one number")
        void typedAndItemisedBothCount() {
            // The owner's explicit call: ₹20,000 declared up front plus ₹75,940 itemised afterwards
            // is ₹95,940 of cost, not ₹75,940. Anything that took only the larger of the two would
            // silently discard the typed figure.
            Booking b = booking("100000.00", "20000.00");
            vendorCostsAre("75940.00");
            internalCostsAre("0");

            service.apply(b);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("4060.00"));
        }

        @Test
        @DisplayName("with an empty ledger it is exactly the old customerAmount − vendorCost")
        void emptyLedgerPreservesLegacyMargin() {
            Booking b = booking("100000.00", "70000.00");
            vendorCostsAre("0");
            internalCostsAre("0");

            service.apply(b);

            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("30000.00"));
        }

        @Test
        @DisplayName("costs can push a thin booking into a loss, and that is reported")
        void negativeProfitIsNotFloored() {
            Booking b = booking("100000.00", "97000.00");
            vendorCostsAre("0");
            internalCostsAre("5000.00");

            service.apply(b);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("-2000.00"));
        }

        @Test
        @DisplayName("every figure is stored at scale 2, whatever scale the inputs carried")
        void scalesToPaise() {
            // @Digits(fraction = 2) caps the input at 2dp but does not force it there — a client may
            // legitimately post "100000" (scale 0). The stored value must still match what the
            // numeric(12,2) column reads back.
            Booking b = booking("100000", "70000");
            vendorCostsAre("2");
            internalCostsAre("1");

            service.apply(b);

            assertThat(b.getNetProfit().scale()).isEqualTo(2);
            assertThat(b.getTotalInternalCosts().scale()).isEqualTo(2);
            assertThat(b.getTotalVendorCosts().scale()).isEqualTo(2);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("29997.00"));
        }

        @Test
        @DisplayName("null money fields are treated as zero rather than throwing")
        void toleratesNulls() {
            Booking b = new Booking();
            b.setId(BOOKING_PK);
            b.setBookingCode("BKG-26-0002");
            vendorCostsAre("0");
            internalCostsAre("0");

            assertThat(service.apply(b)).isFalse();   // 0 − 0 − 0 − 0 == the ZERO defaults ⇒ no change
            assertThat(b.getNetProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a null ledger sum is treated as zero — an empty SUM must not NPE the formula")
        void toleratesNullLedgerSums() {
            // COALESCE keeps the real query at 0, but nothing in the type system says so, and a
            // booking's profit is not a place to find out.
            Booking b = booking("100000.00", "70000.00");
            when(expenseRepository.sumVendorCosts(BOOKING_PK)).thenReturn(null);
            when(expenseRepository.sumInternalCosts(BOOKING_PK)).thenReturn(null);

            service.apply(b);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("30000.00"));
        }
    }

    @Nested
    @DisplayName("apply — idempotence and the no-op guard")
    class Idempotence {

        @Test
        @DisplayName("calling it twice for one event produces the same figure, never a doubled one")
        void recomputesFromSourceInsteadOfAccumulating() {
            Booking b = booking("100000.00", "70000.00");
            vendorCostsAre("0");
            internalCostsAre("8000.00");

            service.apply(b);
            service.apply(b);

            // If apply() subtracted a delta rather than recomputing, the second call would have
            // taken the 8000 off again and left 14000.
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("22000.00"));
        }

        @Test
        @DisplayName("a second call with nothing changed does not save — no Envers revision, no version bump")
        void noOpsWhenNothingMoved() {
            Booking b = booking("100000.00", "70000.00");
            vendorCostsAre("0");
            internalCostsAre("8000.00");

            assertThat(service.apply(b)).isTrue();
            assertThat(service.apply(b)).isFalse();

            verify(bookingRepository, times(1)).save(any(Booking.class));
        }

        @Test
        @DisplayName("0 vs 0.00 is not a change — the guard compares by value, not by scale")
        void scaleDifferenceIsNotAChange() {
            // The trap: BigDecimal.equals() is scale-sensitive, so a guard written with equals()
            // would dirty an audited row on every single call for a booking with no ledger rows
            // — which is most of them.
            Booking b = booking("100000.00", "70000.00");
            b.setTotalInternalCosts(BigDecimal.ZERO);           // scale 0
            b.setTotalVendorCosts(BigDecimal.ZERO);             // scale 0
            b.setNetProfit(bd("30000"));                        // scale 0
            vendorCostsAre("0.00");                             // scale 2
            internalCostsAre("0.00");                           // scale 2

            assertThat(service.apply(b)).isFalse();
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("a new VENDOR expense line alone is enough to dirty the row")
        void vendorLedgerMovementIsAChange() {
            // The guard must watch totalVendorCosts too. Watching only netProfit would be subtly
            // wrong on a cancelled booking, where netProfit is driven by the frozen record and would
            // not move even though the stored cost breakdown did.
            Booking b = booking("100000.00", "70000.00");
            vendorCostsAre("0");
            internalCostsAre("0");
            assertThat(service.apply(b)).isTrue();

            when(expenseRepository.sumVendorCosts(BOOKING_PK)).thenReturn(bd("2500.00"));

            assertThat(service.apply(b)).isTrue();
            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(bd("2500.00"));
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("27500.00"));
        }

        @Test
        @DisplayName("reclassifying a line VENDOR→INTERNAL keeps the margin put — both terms are subtracted")
        void reclassificationIsMarginNeutral() {
            // Before, this MOVED the margin: only INTERNAL rows counted, so flipping the toggle was
            // how a cost entered the calculation at all. Now both classes are subtracted, so the
            // toggle only decides WHICH bucket reports it — supplier cost or agency overhead.
            Booking b = booking("100000.00", "70000.00");
            vendorCostsAre("5000.00");
            internalCostsAre("0");
            service.apply(b);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("25000.00"));

            // The user flips that ₹5,000 "Commission" line from Vendor to Internal.
            when(expenseRepository.sumVendorCosts(BOOKING_PK)).thenReturn(bd("0"));
            when(expenseRepository.sumInternalCosts(BOOKING_PK)).thenReturn(bd("5000.00"));

            assertThat(service.apply(b)).isTrue();
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("25000.00"));
            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(bd("5000.00"));
        }
    }

    @Nested
    @DisplayName("a CANCELLED booking uses the cancellation formula, not the active one")
    class Cancelled {

        /** A frozen cancellation: ₹25,000 retained, ₹70,000 vendor cost sunk. */
        private BookingCancellation cancellationRecord() {
            BookingCancellation c = new BookingCancellation();
            c.setBookingId(BOOKING_PK);
            c.setFinalChargeBase(bd("25000.00"));
            c.setSunkVendorCost(bd("70000.00"));
            return c;
        }

        private void isCancelled(BookingCancellation record) {
            when(cancellationRepository.findByBookingIdAndDeletedAtIsNull(BOOKING_PK))
                    .thenReturn(Optional.of(record));
            when(cancellationRepository.save(any(BookingCancellation.class)))
                    .thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("REGRESSION: an expense edit after cancellation must not reinstate the active formula")
        void usesRetainedChargeMinusSunkCosts() {
            // The trip never happened, so customerAmount was never earned. Applying
            // customerAmount − costs here would report ₹22,000 of profit on a booking that actually
            // LOST money.
            Booking b = booking("100000.00", "70000.00");
            isCancelled(cancellationRecord());
            vendorCostsAre("0");
            internalCostsAre("8000.00");

            assertThat(service.apply(b)).isTrue();

            // 25,000 retained − 70,000 sunk vendor − 8,000 sunk internal = −53,000
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("-53000.00"));
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(bd("8000.00"));
        }

        @Test
        @DisplayName("the cancellation record is kept in step, so the two can never quote different margins")
        void syncsTheFrozenRecord() {
            Booking b = booking("100000.00", "70000.00");
            BookingCancellation record = cancellationRecord();
            isCancelled(record);
            vendorCostsAre("0");
            internalCostsAre("8000.00");

            service.apply(b);

            assertThat(record.getSunkInternalCosts()).isEqualByComparingTo(bd("8000.00"));
            assertThat(record.getRevisedNetProfit()).isEqualByComparingTo(bd("-53000.00"));
            verify(cancellationRepository).save(record);
        }

        @Test
        @DisplayName("the policy side stays frozen — an expense edit never reopens how the customer was charged")
        void chargeBaseIsNeverRecomputed() {
            Booking b = booking("100000.00", "70000.00");
            BookingCancellation record = cancellationRecord();
            isCancelled(record);
            vendorCostsAre("0");
            internalCostsAre("1000.00");

            service.apply(b);

            // Anti-retroactivity: the retained charge and the sunk vendor figure are read, never
            // rewritten. Only the cost term moves, because it genuinely did.
            assertThat(record.getFinalChargeBase()).isEqualByComparingTo(bd("25000.00"));
            assertThat(record.getSunkVendorCost()).isEqualByComparingTo(bd("70000.00"));
        }
    }

    @Nested
    @DisplayName("applyInMemory — the create/convert path")
    class InMemory {

        @Test
        @DisplayName("an unsaved booking skips both ledger queries and is never saved")
        void doesNotSaveOrQueryOnCreate() {
            // On create the row has no id yet, so it can have no expense lines — querying for them
            // would be two pointless round trips against id 0 — and it must not be saved from here,
            // because the caller persists it once, itself.
            Booking b = booking("50000.00", "30000.00");
            b.setId(0L);

            service.applyInMemory(b);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("20000.00"));
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(expenseRepository, never()).sumVendorCosts(any());
            verify(expenseRepository, never()).sumInternalCosts(any());
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("on an EDIT it re-reads both ledger terms itself")
        void readsBothTermsOnEdit() {
            // The reason the cost terms are no longer parameters. The caller used to fetch the
            // internal sum and hand it in; with two terms, a caller that fetched one and forgot the
            // other would silently zero a booking's itemised supplier cost and overstate its profit.
            Booking b = booking("100000.00", "20000.00");
            vendorCostsAre("50000.00");
            internalCostsAre("8000.00");

            service.applyInMemory(b);

            assertThat(b.getTotalVendorCosts()).isEqualByComparingTo(bd("50000.00"));
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(bd("8000.00"));
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("22000.00"));
            verify(bookingRepository, never()).save(any(Booking.class));
        }
    }
}
