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
        b.setNetProfit(BigDecimal.ZERO);
        return b;
    }

    /** What the INTERNAL-only ledger sum returns for this booking. */
    private void internalCostsAre(String amount) {
        when(expenseRepository.sumInternalCosts(BOOKING_PK)).thenReturn(new BigDecimal(amount));
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Nested
    @DisplayName("apply — the formula")
    class Formula {

        @Test
        @DisplayName("netProfit = customerAmount − vendorCost − totalInternalCosts")
        void subtractsBothCostTerms() {
            Booking b = booking("100000.00", "70000.00");
            internalCostsAre("8000.00");

            assertThat(service.apply(b)).isTrue();

            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(bd("8000.00"));
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("22000.00"));
            verify(bookingRepository).save(b);
        }

        @Test
        @DisplayName("with no internal cost rows it is exactly the old customerAmount − vendorCost")
        void zeroInternalCostsPreservesLegacyMargin() {
            // The zero-regression guarantee: every pre-existing row is VENDOR-typed, so the ledger
            // sum is 0 and the stored margin must not move by a paisa on deploy.
            Booking b = booking("100000.00", "70000.00");
            internalCostsAre("0");

            service.apply(b);

            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("30000.00"));
        }

        @Test
        @DisplayName("internal costs can push a thin booking into a loss, and that is reported")
        void negativeProfitIsNotFloored() {
            Booking b = booking("100000.00", "97000.00");
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
            internalCostsAre("1");

            service.apply(b);

            assertThat(b.getNetProfit().scale()).isEqualTo(2);
            assertThat(b.getTotalInternalCosts().scale()).isEqualTo(2);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("29999.00"));
        }

        @Test
        @DisplayName("null money fields are treated as zero rather than throwing")
        void toleratesNulls() {
            Booking b = new Booking();
            b.setId(BOOKING_PK);
            b.setBookingCode("BKG-26-0002");
            internalCostsAre("0");

            assertThat(service.apply(b)).isFalse();   // 0 − 0 − 0 == the ZERO defaults ⇒ no change
            assertThat(b.getNetProfit()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("apply — idempotence and the no-op guard")
    class Idempotence {

        @Test
        @DisplayName("calling it twice for one event produces the same figure, never a doubled one")
        void recomputesFromSourceInsteadOfAccumulating() {
            Booking b = booking("100000.00", "70000.00");
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
            internalCostsAre("8000.00");

            assertThat(service.apply(b)).isTrue();
            assertThat(service.apply(b)).isFalse();

            verify(bookingRepository, times(1)).save(any(Booking.class));
        }

        @Test
        @DisplayName("0 vs 0.00 is not a change — the guard compares by value, not by scale")
        void scaleDifferenceIsNotAChange() {
            // The trap: BigDecimal.equals() is scale-sensitive, so a guard written with equals()
            // would dirty an audited row on every single call for a booking with no internal costs
            // — which is most of them.
            Booking b = booking("100000.00", "70000.00");
            b.setTotalInternalCosts(BigDecimal.ZERO);           // scale 0
            b.setNetProfit(bd("30000"));                        // scale 0
            internalCostsAre("0.00");                           // scale 2

            assertThat(service.apply(b)).isFalse();
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("reclassifying a line VENDOR→INTERNAL moves the margin on the next apply")
        void picksUpALedgerReclassification() {
            Booking b = booking("100000.00", "70000.00");
            internalCostsAre("0");
            service.apply(b);
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("30000.00"));

            // The user flips a ₹5,000 "Commission" line from Vendor to Internal.
            when(expenseRepository.sumInternalCosts(BOOKING_PK)).thenReturn(bd("5000.00"));

            assertThat(service.apply(b)).isTrue();
            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("25000.00"));
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
            // customerAmount − vendorCost − internal here would report ₹22,000 of profit on a
            // booking that actually LOST money.
            Booking b = booking("100000.00", "70000.00");
            isCancelled(cancellationRecord());
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
        @DisplayName("sets both figures without touching the repository")
        void doesNotSave() {
            // On create the row has no id yet, so it can have no expense lines and must not be
            // saved from here — the caller persists it once, itself.
            Booking b = booking("50000.00", "30000.00");

            service.applyInMemory(b, BigDecimal.ZERO);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("20000.00"));
            assertThat(b.getTotalInternalCosts()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        @DisplayName("a null cost term is treated as zero")
        void toleratesNullCosts() {
            Booking b = booking("50000.00", "30000.00");

            service.applyInMemory(b, null);

            assertThat(b.getNetProfit()).isEqualByComparingTo(bd("20000.00"));
        }
    }
}
