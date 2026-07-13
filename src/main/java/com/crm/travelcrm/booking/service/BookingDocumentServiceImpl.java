package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.pdf.BookingDocumentModel;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingPayment;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingPaymentRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingDocumentServiceImpl implements BookingDocumentService {

    @Value("${app.booking.gst-rate:0.05}")
    private BigDecimal gstRate;

    @Value("${app.booking.tcs-rate:0.05}")
    private BigDecimal tcsRate;

    private final BookingRepository            bookingRepository;
    private final CustomerRepository           customerRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final BookingPaymentRepository     paymentRepository;
    private final BookingPdfService            bookingPdfService;

    @Override
    @Transactional(readOnly = true)
    public byte[] renderInvoice(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        List<BookingServiceItem> items = serviceItemRepository
                .findByBookingIdAndDeletedAtIsNullOrderByIdAsc(booking.getId());
        List<BookingPayment> payments = paymentRepository
                .findByBookingIdAndDeletedAtIsNullOrderByPaymentDateDescIdDesc(booking.getId());
        return bookingPdfService.renderInvoice(buildModel(booking, items, payments, "INVOICE"));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderVoucher(UUID bookingPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        List<BookingServiceItem> items = serviceItemRepository
                .findByBookingIdAndDeletedAtIsNullOrderByIdAsc(booking.getId());
        return bookingPdfService.renderVoucher(buildModel(booking, items, List.of(), "VOUCHER"));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderServiceVoucher(UUID bookingPublicId, UUID serviceItemPublicId) {
        Booking booking = findActiveBooking(bookingPublicId);
        BookingServiceItem item = serviceItemRepository
                .findByPublicIdAndBookingIdAndDeletedAtIsNull(serviceItemPublicId, booking.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Service line not found on this booking: " + serviceItemPublicId));
        return bookingPdfService.renderVoucher(buildModel(booking, List.of(item), List.of(), "VOUCHER"));
    }

    // ── Model assembly ───────────────────────────────────────────────────────

    private BookingDocumentModel buildModel(Booking booking,
                                            List<BookingServiceItem> items,
                                            List<BookingPayment> payments,
                                            String documentType) {

        Long tenantId = TenantContext.getTenantId();
        Customer customer = (tenantId != null && booking.getCustomerId() != null)
                ? customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(booking.getCustomerId(), tenantId).orElse(null)
                : null;

        BookingDocumentModel.Party party = BookingDocumentModel.Party.builder()
                .name(customer != null ? customer.getName() : booking.getCustomerNameSnapshot())
                .code(customer != null ? customer.getCustomerCode() : null)
                .phone(customer != null ? customer.getPhone() : null)
                .email(customer != null ? customer.getEmail() : null)
                .address(customer != null ? customer.getAddress() : null)
                .city(customer != null ? customer.getCity() : null)
                .state(customer != null ? customer.getState() : null)
                .pincode(customer != null ? customer.getPincode() : null)
                .panNo(customer != null ? customer.getPanNo() : null)
                .build();

        List<BookingDocumentModel.Line> lines = items.stream()
                .map(i -> BookingDocumentModel.Line.builder()
                        .serviceType(i.getServiceType())
                        .title(i.getTitle())
                        .description(i.getDescription())
                        .serviceDate(i.getServiceDate())
                        .endDate(i.getEndDate())
                        .status(i.getStatus() != null ? i.getStatus().name() : null)
                        .cost(i.getCost())
                        .vendorName(i.getVendorNameSnapshot())
                        .confirmationNumber(i.getConfirmationNumber())
                        .build())
                .toList();

        List<BookingDocumentModel.Receipt> receipts = payments.stream()
                .filter(p -> !p.isRefund())   // refunds are money OUT — never a "Payment Received"
                .map(p -> BookingDocumentModel.Receipt.builder()
                        .paymentDate(p.getPaymentDate())
                        .amount(p.getAmount())
                        .method(p.getPaymentMethod())
                        .reference(p.getReference())
                        .paymentType(p.getPaymentType())
                        .build())
                .toList();

        // Once a booking is cancelled/refunded, the ORIGINAL invoice no longer represents a live
        // receivable — the cancellation note supersedes it and settlement moves onto that document.
        // Voiding the balance-due here stops the reprinted invoice from implying the customer still
        // owes the pre-cancellation pending amount.
        boolean voided = booking.getStatus() == BookingStatus.CANCELLED
                || booking.getStatus() == BookingStatus.REFUNDED;
        BigDecimal pendingAmount = voided ? BigDecimal.ZERO : booking.getPendingAmount();

        return BookingDocumentModel.builder()
                .documentType(documentType)
                .bookingCode(booking.getBookingCode())
                .status(booking.getStatus() != null ? booking.getStatus().name() : null)
                .paymentStatus(booking.getPaymentStatus() != null ? booking.getPaymentStatus().name() : null)
                .bookingDate(booking.getBookingDate())
                .travelDate(booking.getTravelDate())
                .destination(booking.getDestinationSnapshot())
                .createdBy(booking.getCreatedBy())
                .generatedOn(LocalDate.now())
                .customer(party)
                .customerAmount(booking.getCustomerAmount())
                .gst(booking.getGst())
                .tcs(booking.getTcs())
                .totalPayable(booking.getTotalPayable())
                .paidAmount(booking.getPaidAmount())
                .pendingAmount(pendingAmount)
                .gstRatePct(asPercent(gstRate))
                .tcsRatePct(asPercent(tcsRate))
                .services(lines)
                .payments(receipts)
                .build();
    }

    private Booking findActiveBooking(UUID bookingPublicId) {
        return bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
    }

    /** "0.05" → "5" (strips trailing zeros so 0.05→5, 0.18→18, 0.125→12.5). */
    private static String asPercent(BigDecimal rate) {
        if (rate == null) return "0";
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }
}