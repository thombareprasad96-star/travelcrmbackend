package com.crm.travelcrm.booking.cancellation;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.BookingDocument;
import com.crm.travelcrm.booking.cancellation.enums.RefundStatus;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.cancellation.service.BookingRefundServiceImpl;
import com.crm.travelcrm.booking.cancellation.service.CancellationDocumentService;
import com.crm.travelcrm.booking.dto.request.RefundBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.RefundResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentEntryType;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.subagent.service.SubAgentCommissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refund payout tests, in the booking module's established style: plain JUnit 5, hand-rolled
 * Mockito mocks, no Spring context.
 *
 * <p>These pin two defects that were live and are easy to reintroduce:
 * <ol>
 *   <li><b>Idempotency ordering.</b> The "already fully refunded" 409 used to run BEFORE the
 *       idempotency lookup. Since the payout that COMPLETES a refund leaves {@code remaining == 0},
 *       an honest retry of that exact request — the network timed out, the money actually went out
 *       — was answered with an error instead of the original receipt. Client retries are the whole
 *       reason the idempotency key exists, and the guard defeated it precisely in the case that
 *       matters most: the final (often only) disbursement.</li>
 *   <li><b>Sub-agent row scope.</b> The refund path resolved the booking directly instead of
 *       through the guarded chokepoint every other by-id booking operation uses, so a SUB_AGENT
 *       holding BOOKING_REFUND could pay out against a peer's booking. Tenant isolation still held;
 *       this was intra-tenant.</li>
 * </ol>
 */
class BookingRefundServiceImplTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final long BOOKING_PK = 42L;
    private static final String IDEM_KEY = "rf-abc-123";

    private BookingRepository             bookingRepository;
    private BookingPaymentRepository      paymentRepository;
    private BookingCancellationRepository cancellationRepository;
    private CancellationDocumentService   documentService;
    private SubAgentCommissionService     commissionService;
    private SubAgentScope                 subAgentScope;
    private BookingRefundServiceImpl      service;

    private Booking             booking;
    private BookingCancellation record;

    @BeforeEach
    void setUp() {
        bookingRepository      = mock(BookingRepository.class);
        paymentRepository      = mock(BookingPaymentRepository.class);
        cancellationRepository = mock(BookingCancellationRepository.class);
        documentService        = mock(CancellationDocumentService.class);
        commissionService      = mock(SubAgentCommissionService.class);
        subAgentScope          = mock(SubAgentScope.class);

        service = new BookingRefundServiceImpl(bookingRepository, paymentRepository,
                cancellationRepository, documentService, commissionService, subAgentScope);

        booking = new Booking();
        booking.setId(BOOKING_PK);
        booking.setPublicId(BOOKING_ID);
        booking.setBookingCode("BKG-26-0001");
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setRefundedAmount(BigDecimal.ZERO);

        // Frozen at cancel time: ₹30,000 due back to the customer.
        record = new BookingCancellation();
        record.setBookingId(BOOKING_PK);
        record.setRefundDue(new BigDecimal("30000.00"));
        record.setCustomerOwes(false);
        record.setRefundStatus(RefundStatus.PENDING);

        when(bookingRepository.findByPublicIdAndDeletedAtIsNull(BOOKING_ID))
                .thenReturn(Optional.of(booking));
        when(cancellationRepository.findByBookingIdAndDeletedAtIsNull(BOOKING_PK))
                .thenReturn(Optional.of(record));
        when(paymentRepository.save(any(BookingPayment.class)))
                .thenAnswer(i -> {
                    BookingPayment p = i.getArgument(0);
                    p.setPublicId(UUID.randomUUID());
                    return p;
                });
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));
        when(cancellationRepository.save(any(BookingCancellation.class))).thenAnswer(i -> i.getArgument(0));

        BookingDocument voucher = new BookingDocument();
        voucher.setPublicId(UUID.randomUUID());
        voucher.setDocNumber("RFV-26-0001");
        when(documentService.issueRefundVoucher(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(voucher);
        // Default: no prior payout for any key.
        when(paymentRepository.findByBookingIdAndIdempotencyKeyAndDeletedAtIsNull(eq(BOOKING_PK), anyString()))
                .thenReturn(Optional.empty());
    }

    private RefundBookingRequestDTO request(String amount, String idemKey) {
        RefundBookingRequestDTO r = new RefundBookingRequestDTO();
        r.setAmount(new BigDecimal(amount));
        r.setMethod("UPI");
        r.setReference("UTR123");
        r.setRefundDate(LocalDate.of(2026, 1, 10));
        r.setIdempotencyKey(idemKey);
        return r;
    }

    /** The ledger row a prior successful payout of {@code amount} would have left behind. */
    private BookingPayment priorPayout(String amount) {
        return BookingPayment.builder()
                .bookingId(BOOKING_PK)
                .amount(new BigDecimal(amount))
                .entryType(PaymentEntryType.REFUND)
                .paymentType("Refund")
                .paymentMethod("UPI")
                .paymentDate(LocalDate.of(2026, 1, 10))
                .reference("UTR123")
                .idempotencyKey(IDEM_KEY)
                .build();
    }

    @Nested
    @DisplayName("idempotency ordering")
    class Idempotency {

        @Test
        @DisplayName("REGRESSION: retrying the payout that FULLY refunded the booking echoes it, not a 409")
        void retryOfTheFinalPayoutIsEchoedNot409() {
            // State after the full ₹30,000 went out: remaining == 0. The old ordering hit the
            // "already fully refunded" guard first and 409'd a request that had actually succeeded.
            booking.setRefundedAmount(new BigDecimal("30000.00"));
            booking.setStatus(BookingStatus.REFUNDED);
            record.setRefundStatus(RefundStatus.PAID);
            BookingPayment prior = priorPayout("30000.00");
            prior.setPublicId(UUID.randomUUID());
            when(paymentRepository.findByBookingIdAndIdempotencyKeyAndDeletedAtIsNull(BOOKING_PK, IDEM_KEY))
                    .thenReturn(Optional.of(prior));

            RefundResponseDTO response = service.refund(BOOKING_ID, request("30000.00", IDEM_KEY));

            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("30000.00"));
            assertThat(response.getTotalRefunded()).isEqualByComparingTo(new BigDecimal("30000.00"));
            assertThat(response.getRemainingRefundable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.isFullyRefunded()).isTrue();

            // Nothing was disbursed a second time.
            verify(paymentRepository, never()).save(any(BookingPayment.class));
            verify(documentService, never()).issueRefundVoucher(any(), any(), any(), any(), any(), any(), any());
            verify(commissionService, never()).syncForBooking(any());
        }

        @Test
        @DisplayName("retrying a PARTIAL payout also echoes without disbursing again")
        void retryOfAPartialPayoutIsEchoed() {
            booking.setRefundedAmount(new BigDecimal("10000.00"));
            record.setRefundStatus(RefundStatus.PARTIALLY_PAID);
            BookingPayment prior = priorPayout("10000.00");
            prior.setPublicId(UUID.randomUUID());
            when(paymentRepository.findByBookingIdAndIdempotencyKeyAndDeletedAtIsNull(BOOKING_PK, IDEM_KEY))
                    .thenReturn(Optional.of(prior));

            RefundResponseDTO response = service.refund(BOOKING_ID, request("10000.00", IDEM_KEY));

            assertThat(response.getTotalRefunded()).isEqualByComparingTo(new BigDecimal("10000.00"));
            assertThat(response.getRemainingRefundable()).isEqualByComparingTo(new BigDecimal("20000.00"));
            assertThat(response.isFullyRefunded()).isFalse();
            verify(paymentRepository, never()).save(any(BookingPayment.class));
        }

        @Test
        @DisplayName("a NEW key on an already fully-refunded booking still 409s")
        void freshKeyOnASettledBookingStill409s() {
            // The economic cap must survive the reordering: only an echo of a KNOWN key skips it.
            booking.setRefundedAmount(new BigDecimal("30000.00"));

            assertThatThrownBy(() -> service.refund(BOOKING_ID, request("1000.00", "different-key")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already been fully refunded");

            verify(paymentRepository, never()).save(any(BookingPayment.class));
        }
    }

    @Nested
    @DisplayName("a normal disbursement")
    class HappyPath {

        @Test
        @DisplayName("writes a REFUND ledger row, accrues refundedAmount, and never touches paidAmount")
        void firstPartialPayout() {
            booking.setPaidAmount(new BigDecimal("50000.00"));

            RefundResponseDTO response = service.refund(BOOKING_ID, request("12000.00", IDEM_KEY));

            assertThat(booking.getRefundedAmount()).isEqualByComparingTo(new BigDecimal("12000.00"));
            assertThat(booking.getPaidAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);   // not yet fully refunded
            assertThat(response.getRemainingRefundable()).isEqualByComparingTo(new BigDecimal("18000.00"));

            ArgumentCaptor<BookingPayment> ledgerRow = ArgumentCaptor.forClass(BookingPayment.class);
            verify(paymentRepository).save(ledgerRow.capture());
            assertThat(ledgerRow.getValue().getEntryType()).isEqualTo(PaymentEntryType.REFUND);
            assertThat(ledgerRow.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("12000.00"));
            assertThat(ledgerRow.getValue().getIdempotencyKey()).isEqualTo(IDEM_KEY);

            verify(commissionService).syncForBooking(booking);
        }

        @Test
        @DisplayName("the payout that clears the balance flips the booking to REFUNDED")
        void finalPayoutFlipsStatus() {
            booking.setRefundedAmount(new BigDecimal("20000.00"));

            RefundResponseDTO response = service.refund(BOOKING_ID, request("10000.00", IDEM_KEY));

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.REFUNDED);
            assertThat(record.getRefundStatus()).isEqualTo(RefundStatus.PAID);
            assertThat(response.isFullyRefunded()).isTrue();
        }

        @Test
        @DisplayName("a payout above the remaining balance is refused")
        void overPayoutIsCapped() {
            booking.setRefundedAmount(new BigDecimal("25000.00"));

            assertThatThrownBy(() -> service.refund(BOOKING_ID, request("10000.00", IDEM_KEY)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("still refundable");
        }
    }

    @Nested
    @DisplayName("row-level scope")
    class Scoping {

        @Test
        @DisplayName("REGRESSION: a sub-agent who does not own the booking gets 404 before anything moves")
        void foreignBookingIs404() {
            Mockito.doThrow(new ResourceNotFoundException("Not found: " + BOOKING_ID))
                    .when(subAgentScope).assertVisible(any(), eq(BOOKING_ID));

            assertThatThrownBy(() -> service.refund(BOOKING_ID, request("1000.00", IDEM_KEY)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(paymentRepository, never()).save(any(BookingPayment.class));
            verify(bookingRepository, never()).save(any(Booking.class));
        }
    }
}
