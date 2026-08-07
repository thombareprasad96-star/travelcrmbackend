package com.crm.travelcrm.hotelmarketplace;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ApproveMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.CancelMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.QuoteCancellationRequest;
import com.crm.travelcrm.hotelmarketplace.booking.dto.ReviseMarketplaceBookingRequest;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.booking.service.MarketplacePlatformWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The marketplace booking state machine.
 *
 * <p>Every transition here is guarded, and the guards are the product: a request that can be
 * confirmed at a price the tenant never agreed to, or read by the wrong tenant, is not a bug in a
 * feature — it is the feature failing at the only two things it exists to get right (design §8
 * Step 6B, §11).</p>
 */
class MarketplaceBookingStateMachineTest {

    private static final Long TENANT = 7L;
    private static final Long OTHER_TENANT = 8L;

    private PlatformHotelBookingRepository repository;
    private MarketplacePlatformWriter writer;
    private PlatformHotelBooking row;
    private UUID publicId;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHotelBookingRepository.class);
        writer = new MarketplacePlatformWriter(repository);

        publicId = UUID.randomUUID();
        row = new PlatformHotelBooking();
        row.setPublicId(publicId);
        row.setTenantId(TENANT);
        row.setBookingCode("MKT-2026-ABCD1234");
        row.setHotelNameSnapshot("Taj Exotica");
        row.setCheckIn(LocalDate.of(2026, 8, 10));
        row.setCheckOut(LocalDate.of(2026, 8, 12));
        row.setStatus(MarketplaceBookingStatus.REQUESTED);
        row.setRevisionCount(0);
        row.setCurrency("INR");

        when(repository.findByPublicIdForUpdate(publicId)).thenReturn(Optional.of(row));
        when(repository.save(any(PlatformHotelBooking.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Approval ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Approval")
    class Approval {

        @Test
        @DisplayName("platform earning is the difference, derived rather than accepted from the caller")
        void earningIsDerived() {
            PlatformHotelBooking result = writer.confirm(publicId, approve("4000", "4400"), 1L);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CONFIRMED);
            assertThat(result.getSupplierTotal()).isEqualByComparingTo("4000.00");
            assertThat(result.getTenantPayable()).isEqualByComparingTo("4400.00");
            assertThat(result.getPlatformEarning()).isEqualByComparingTo("400.00");
        }

        @Test
        @DisplayName("a payable below the supplier total is refused, not silently absorbed")
        void refusesNegativeEarning() {
            assertThatThrownBy(() -> writer.confirm(publicId, approve("4000", "3900"), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("below the supplier total");
        }

        @Test
        @DisplayName("re-approving a CONFIRMED booking returns it unchanged instead of re-running")
        void approveIsIdempotent() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            PlatformHotelBooking result = writer.confirm(publicId, approve("9999", "9999"), 1L);

            assertThat(result.getTenantPayable()).isEqualByComparingTo("4400.00");
        }

        @Test
        @DisplayName("TENANT_APPROVAL_REQUIRED is not approvable — an unanswered offer cannot be confirmed")
        void cannotApproveWhileAwaitingTenant() {
            row.setStatus(MarketplaceBookingStatus.TENANT_APPROVAL_REQUIRED);

            assertThatThrownBy(() -> writer.confirm(publicId, approve("4000", "4400"), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("can no longer be approved");
        }

        @Test
        @DisplayName("after acceptance, approving at a DIFFERENT amount is a conflict, not a silent repricing")
        void cannotApproveAtAnAmountTheTenantNeverAccepted() {
            row.setStatus(MarketplaceBookingStatus.TENANT_ACCEPTED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            assertThatThrownBy(() -> writer.confirm(publicId, approve("4500", "5000"), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("accepted 4400.00");
        }

        @Test
        @DisplayName("after acceptance, approving at the accepted amount succeeds")
        void approvesAtTheAcceptedAmount() {
            row.setStatus(MarketplaceBookingStatus.TENANT_ACCEPTED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            PlatformHotelBooking result = writer.confirm(publicId, approve("4000", "4400"), 1L);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CONFIRMED);
        }
    }

    // ── Price revision ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Price revision")
    class Revision {

        @Test
        @DisplayName("the proposal lands beside the agreed money, never on top of it")
        void proposalDoesNotTouchTheLivePayable() {
            row.setStatus(MarketplaceBookingStatus.UNDER_REVIEW);
            row.setTenantPayable(new BigDecimal("4400.00"));

            PlatformHotelBooking result = writer.requestRevision(publicId, revise("4600", "5000"), 1L, 48);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.TENANT_APPROVAL_REQUIRED);
            assertThat(result.getTenantPayable()).isEqualByComparingTo("4400.00");
            assertThat(result.getRevisedTenantPayable()).isEqualByComparingTo("5000.00");
            assertThat(result.getRevisionPreviousPayable()).isEqualByComparingTo("4400.00");
            assertThat(result.getRevisionCount()).isEqualTo(1);
            assertThat(result.getRevisionExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("acceptance promotes the proposal and re-derives the earning")
        void acceptancePromotes() {
            givenOpenRevision("4600", "5000");

            PlatformHotelBooking result = writer.acceptRevision(publicId, TENANT);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.TENANT_ACCEPTED);
            assertThat(result.getTenantPayable()).isEqualByComparingTo("5000.00");
            assertThat(result.getSupplierTotal()).isEqualByComparingTo("4600.00");
            assertThat(result.getPlatformEarning()).isEqualByComparingTo("400.00");
            assertThat(result.getRevisionRespondedAt()).isNotNull();
            // The offer is consumed, not left lying around looking open.
            assertThat(result.getRevisedTenantPayable()).isNull();
            assertThat(result.getRevisionExpiresAt()).isNull();
            assertThat(result.getRevisionPreviousPayable()).isNull();   // none was set in this fixture
        }

        @Test
        @DisplayName("an expired offer cannot be accepted — the supplier rate is long gone")
        void expiredOfferIsRefused() {
            givenOpenRevision("4600", "5000");
            row.setRevisionExpiresAt(LocalDateTime.now().minusHours(1));

            assertThatThrownBy(() -> writer.acceptRevision(publicId, TENANT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("declining ends the request and records that the TENANT refused")
        void declineTerminates() {
            givenOpenRevision("4600", "5000");

            PlatformHotelBooking result = writer.declineRevision(publicId, TENANT, "Customer said no");

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.REJECTED);
            assertThat(result.getRejectionReason())
                    .contains("Tenant declined")
                    .contains("Customer said no");
        }

        @Test
        @DisplayName("expiry clears the stale price so it can never be accepted later")
        void expiryClearsTheOffer() {
            givenOpenRevision("4600", "5000");

            PlatformHotelBooking result = writer.expireRevision(publicId);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.UNDER_REVIEW);
            assertThat(result.getRevisedTenantPayable()).isNull();
            assertThat(result.getRevisedSupplierTotal()).isNull();
            assertThat(result.getRevisionExpiresAt()).isNull();
        }

        @Test
        @DisplayName("accepting when nothing was offered is a conflict, not a no-op")
        void nothingToAccept() {
            row.setStatus(MarketplaceBookingStatus.UNDER_REVIEW);

            assertThatThrownBy(() -> writer.acceptRevision(publicId, TENANT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no open price revision");
        }
    }

    // ── Cancellation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        @Test
        @DisplayName("a CONFIRMED booking can only be REQUESTED for cancellation, never cancelled outright")
        void confirmedGoesThroughTheQueue() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);

            PlatformHotelBooking result = writer.requestCancellation(publicId, TENANT, "Trip called off");

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCEL_REQUESTED);
            assertThat(result.getCancelRequestedAt()).isNotNull();
        }

        @Test
        @DisplayName("an uncommitted request is withdrawn free and immediately")
        void withdrawIsFree() {
            row.setStatus(MarketplaceBookingStatus.REQUESTED);

            PlatformHotelBooking result = writer.withdraw(publicId, TENANT, "Changed our minds");

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCELLED);
            assertThat(result.getCancellationCharge()).isEqualByComparingTo("0.00");
            assertThat(result.getTenantRefundAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a confirmed booking cannot be withdrawn, and the error says what to do instead")
        void confirmedCannotBeWithdrawn() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);

            assertThatThrownBy(() -> writer.withdraw(publicId, TENANT, "nope"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("request a cancellation instead");
        }

        @Test
        @DisplayName("the refund is derived from the charge, never posted by a client")
        void refundIsDerived() {
            row.setStatus(MarketplaceBookingStatus.CANCEL_REQUESTED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            PlatformHotelBooking result = writer.settleCancellation(publicId, cancel("1000", "0"), 1L);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCELLED);
            assertThat(result.getCancellationCharge()).isEqualByComparingTo("1000.00");
            assertThat(result.getTenantRefundAmount()).isEqualByComparingTo("3400.00");
        }

        @Test
        @DisplayName("a charge above the payable is refused — it would mint a negative refund")
        void chargeCannotExceedPayable() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            assertThatThrownBy(() -> writer.settleCancellation(publicId, cancel("5000", "0"), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exceeds what the tenant owes");
        }

        @Test
        @DisplayName("the platform cannot retain more commission than the charge it collected")
        void retainedEarningCannotExceedTheCharge() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            assertThatThrownBy(() -> writer.settleCancellation(publicId, cancel("500", "800"), 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("out of the tenant's refund");
        }

        @Test
        @DisplayName("a CRM cancellation raises the platform row for an operator, it does not settle it")
        void crmCancellationRaisesRatherThanSettles() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);

            Optional<PlatformHotelBooking> result =
                    writer.onCrmBookingCancelled(publicId, TENANT, "Customer cancelled the trip");

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(MarketplaceBookingStatus.CANCEL_REQUESTED);
            assertThat(result.get().getCancelRequestReason()).contains("CRM booking was cancelled");
        }

        @Test
        @DisplayName("a CRM cancellation on an already-terminal row changes nothing")
        void crmCancellationIgnoresTerminalRows() {
            row.setStatus(MarketplaceBookingStatus.CANCELLED);

            assertThat(writer.onCrmBookingCancelled(publicId, TENANT, "again")).isEmpty();
        }
    }

    // ── Cancellation consent (design §9 clauses 1-3) ───────────────────────

    @Nested
    @DisplayName("Cancellation consent")
    class CancellationConsent {

        @Test
        @DisplayName("the quote lands beside the settled figures, never on top of them")
        void quoteDoesNotSettle() {
            row.setStatus(MarketplaceBookingStatus.CANCEL_REQUESTED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            PlatformHotelBooking result = writer.quoteCancellation(publicId, quote("1000", "200"), 1L, 24);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCELLATION_QUOTED);
            assertThat(result.getQuotedCancellationCharge()).isEqualByComparingTo("1000.00");
            // Not settled: nobody has agreed to anything yet.
            assertThat(result.getCancellationCharge()).isNull();
            assertThat(result.getTenantRefundAmount()).isNull();
            assertThat(result.getCancelledAt()).isNull();
            assertThat(result.getCancellationQuoteExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("a quote above the payable is refused at QUOTE time, not only at settle time")
        void quoteValidatesTheSameAsSettle() {
            row.setStatus(MarketplaceBookingStatus.CANCEL_REQUESTED);
            row.setTenantPayable(new BigDecimal("4400.00"));

            assertThatThrownBy(() -> writer.quoteCancellation(publicId, quote("5000", "0"), 1L, 24))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exceeds what the tenant owes");

            assertThatThrownBy(() -> writer.quoteCancellation(publicId, quote("500", "800"), 1L, 24))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("out of the tenant's refund");
        }

        @Test
        @DisplayName("acceptance settles at the QUOTED figure — not at anything the caller supplies")
        void acceptanceSettlesAtTheQuotedFigure() {
            givenOpenCancellationQuote("1000");

            PlatformHotelBooking result = writer.acceptCancellationQuote(publicId, TENANT);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCELLED);
            assertThat(result.getCancellationCharge()).isEqualByComparingTo("1000.00");
            assertThat(result.getTenantRefundAmount()).isEqualByComparingTo("3400.00");
            assertThat(result.getCancelledAt()).isNotNull();
        }

        @Test
        @DisplayName("an expired quote cannot be accepted — the hotel's waiver has lapsed")
        void expiredQuoteIsRefused() {
            givenOpenCancellationQuote("1000");
            row.setCancellationQuoteExpiresAt(LocalDateTime.now().minusHours(1));

            assertThatThrownBy(() -> writer.acceptCancellationQuote(publicId, TENANT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        @DisplayName("declining keeps the booking — the room was never released")
        void declineKeepsTheBooking() {
            givenOpenCancellationQuote("1000");

            PlatformHotelBooking result = writer.declineCancellationQuote(publicId, TENANT, "Too expensive");

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CONFIRMED);
            assertThat(result.getCancelledAt()).isNull();
            assertThat(result.getCancellationCharge()).isNull();
            assertThat(result.getQuotedCancellationCharge()).isNull();
        }

        @Test
        @DisplayName("expiry clears the stale charge and returns it to the platform queue")
        void expiryReturnsToQueue() {
            givenOpenCancellationQuote("1000");

            PlatformHotelBooking result = writer.expireCancellationQuote(publicId);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCEL_REQUESTED);
            assertThat(result.getQuotedCancellationCharge()).isNull();
            assertThat(result.getCancellationQuoteExpiresAt()).isNull();
            // Crucially NOT cancelled: being slow to reply must never cost the tenant a booking.
            assertThat(result.getCancelledAt()).isNull();
        }

        @Test
        @DisplayName("a cancellation quote cannot be accepted as though it were a price revision")
        void quoteIsNotARevision() {
            givenOpenCancellationQuote("1000");

            assertThatThrownBy(() -> writer.acceptRevision(publicId, TENANT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("no open price revision");
        }

        @Test
        @DisplayName("the SuperAdmin override still works, and clears any open quote behind it")
        void adminOverrideStillWorks() {
            givenOpenCancellationQuote("1000");

            PlatformHotelBooking result = writer.settleCancellation(publicId, cancel("750", "0"), 1L);

            assertThat(result.getStatus()).isEqualTo(MarketplaceBookingStatus.CANCELLED);
            assertThat(result.getCancellationCharge()).isEqualByComparingTo("750.00");
            assertThat(result.getQuotedCancellationCharge()).isNull();
            assertThat(result.getCancelledBySuperAdminId()).isEqualTo(1L);
        }
    }

    // ── Tenant isolation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tenant isolation")
    class Isolation {

        /**
         * These rows extend {@code BaseEntity}, so no Hibernate filter scopes them and
         * {@code TenantIsolationArchTest} does not cover the repository either. The ownership check
         * inside the writer is the ONLY thing standing between tenant A and tenant B's negotiated
         * payable and guest details.
         */
        @Test
        @DisplayName("a foreign booking is 404, never 403 — existence itself must not leak")
        void foreignTenantGets404() {
            row.setStatus(MarketplaceBookingStatus.CONFIRMED);

            assertThatThrownBy(() -> writer.requestCancellation(publicId, OTHER_TENANT, "not mine"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("every tenant-initiated transition is scoped, not just the reads")
        void allTenantTransitionsAreScoped() {
            givenOpenRevision("4600", "5000");

            assertThatThrownBy(() -> writer.acceptRevision(publicId, OTHER_TENANT))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> writer.declineRevision(publicId, OTHER_TENANT, "x"))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> writer.withdraw(publicId, OTHER_TENANT, "x"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a null tenant context cannot pass the ownership check")
        void nullTenantIsRefused() {
            assertThatThrownBy(() -> writer.withdraw(publicId, null, "x"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void givenOpenRevision(String supplier, String payable) {
        row.setStatus(MarketplaceBookingStatus.TENANT_APPROVAL_REQUIRED);
        row.setRevisedSupplierTotal(new BigDecimal(supplier));
        row.setRevisedTenantPayable(new BigDecimal(payable));
        row.setRevisionRequestedAt(LocalDateTime.now().minusHours(1));
        row.setRevisionExpiresAt(LocalDateTime.now().plusHours(47));
        row.setRevisionCount(1);
    }

    private static ApproveMarketplaceBookingRequest approve(String supplier, String payable) {
        ApproveMarketplaceBookingRequest cmd = new ApproveMarketplaceBookingRequest();
        cmd.setSupplierTotal(new BigDecimal(supplier));
        cmd.setTenantPayable(new BigDecimal(payable));
        cmd.setSupplierConfirmationNumber("HTL-99");
        return cmd;
    }

    private static ReviseMarketplaceBookingRequest revise(String supplier, String payable) {
        ReviseMarketplaceBookingRequest cmd = new ReviseMarketplaceBookingRequest();
        cmd.setRevisedSupplierTotal(new BigDecimal(supplier));
        cmd.setRevisedTenantPayable(new BigDecimal(payable));
        cmd.setReason("The hotel raised its rate for these dates");
        return cmd;
    }

    private void givenOpenCancellationQuote(String charge) {
        row.setStatus(MarketplaceBookingStatus.CANCELLATION_QUOTED);
        row.setTenantPayable(new BigDecimal("4400.00"));
        row.setQuotedCancellationCharge(new BigDecimal(charge));
        row.setQuotedRetainedEarning(BigDecimal.ZERO);
        row.setCancellationQuoteNote("Inside the hotel's 48-hour window; they retain one night.");
        row.setCancellationQuotedAt(LocalDateTime.now().minusHours(1));
        row.setCancellationQuoteExpiresAt(LocalDateTime.now().plusHours(23));
    }

    private static QuoteCancellationRequest quote(String charge, String retained) {
        QuoteCancellationRequest cmd = new QuoteCancellationRequest();
        cmd.setCancellationCharge(new BigDecimal(charge));
        cmd.setRetainedPlatformEarning(new BigDecimal(retained));
        cmd.setNote("Inside the hotel's 48-hour window; they retain one night.");
        return cmd;
    }

    private static CancelMarketplaceBookingRequest cancel(String charge, String retained) {
        CancelMarketplaceBookingRequest cmd = new CancelMarketplaceBookingRequest();
        cmd.setCancellationCharge(new BigDecimal(charge));
        cmd.setRetainedPlatformEarning(new BigDecimal(retained));
        cmd.setReason("Guest cancelled");
        return cmd;
    }
}
