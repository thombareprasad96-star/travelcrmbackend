package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationDocumentModel;
import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.entity.BookingDocument;
import com.crm.travelcrm.booking.cancellation.enums.DocumentType;
import com.crm.travelcrm.booking.cancellation.repository.BookingDocumentRepository;
import com.crm.travelcrm.booking.cancellation.util.DocumentNumberGenerator;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CancellationDocumentServiceImpl implements CancellationDocumentService {

    private static final Logger log = LogManager.getLogger(CancellationDocumentServiceImpl.class);
    private static final List<DocumentType> NOTE_TYPES =
            List.of(DocumentType.CREDIT_NOTE, DocumentType.DEBIT_NOTE);

    private final BookingDocumentRepository documentRepository;
    private final DocumentNumberGenerator numberGenerator;
    private final CancellationPdfService pdfService;
    private final ObjectMapper objectMapper;
    private final CompanyRepository companyRepository;
    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;
    private final SubAgentBrandingService brandingService;

    @Value("${quotation.pdf.company-name:TravelCRM}")     private String defCompanyName;
    @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") private String defTagline;
    @Value("${quotation.pdf.company-phone:}")             private String defPhone;
    @Value("${quotation.pdf.company-email:}")             private String defEmail;
    @Value("${quotation.pdf.company-website:}")           private String defWebsite;
    @Value("${quotation.pdf.company-address:}")           private String defAddress;
    @Value("${quotation.pdf.company-logo-url:}")          private String defLogo;
    @Value("${booking.pdf.brand-color:#B8891F}")          private String brandColor;

    // ── Issue (inside the cancel transaction) ─────────────────────────────────

    @Override
    public BookingDocument issueCancellationNote(Booking booking, BookingCancellation record,
                                                 CancellationQuote quote) {
        Long tenantId = booking.getTenantId();
        DocumentType docType = quote.isCustomerOwes() ? DocumentType.DEBIT_NOTE : DocumentType.CREDIT_NOTE;
        String number = numberGenerator.next(tenantId, docType);

        CancellationDocumentModel model = buildNoteModel(booking, record, quote, docType, number);
        BookingDocument doc = BookingDocument.builder()
                .tenantId(tenantId)
                .bookingId(booking.getId())
                .cancellationId(record.getId())
                .docType(docType)
                .docNumber(number)
                .modelSnapshotJson(toJson(model))
                .issuedAt(LocalDateTime.now())
                .issuedByEmail(currentUserEmail())
                .build();
        BookingDocument saved = documentRepository.save(doc);

        record.setCreditNoteNumber(number);
        record.setCreditNoteDocPublicId(saved.getPublicId());
        log.info("Issued {} {} for booking {}", docType, number, booking.getBookingCode());
        return saved;
    }

    @Override
    public BookingDocument issueRefundVoucher(Booking booking, BookingCancellation record,
                                              BigDecimal amount, String method, String reference,
                                              LocalDate refundDate, BigDecimal totalRefundedToDate) {
        Long tenantId = booking.getTenantId();
        String number = numberGenerator.next(tenantId, DocumentType.REFUND_VOUCHER);

        CancellationDocumentModel model = buildVoucherModel(booking, record, number,
                amount, method, reference, refundDate, totalRefundedToDate);
        BookingDocument doc = BookingDocument.builder()
                .tenantId(tenantId)
                .bookingId(booking.getId())
                .cancellationId(record.getId())
                .docType(DocumentType.REFUND_VOUCHER)
                .docNumber(number)
                .modelSnapshotJson(toJson(model))
                .issuedAt(LocalDateTime.now())
                .issuedByEmail(currentUserEmail())
                .build();
        BookingDocument saved = documentRepository.save(doc);
        log.info("Issued REFUND_VOUCHER {} (Rs.{}) for booking {}", number, amount, booking.getBookingCode());
        return saved;
    }

    // ── Render + freeze (lazy, out of the cancel transaction) ─────────────────

    @Override
    @Transactional
    public byte[] renderCancellationNote(UUID bookingPublicId) {
        Booking booking = loadBooking(bookingPublicId);
        BookingDocument doc = documentRepository
                .findFirstByBookingIdAndDocTypeInAndDeletedAtIsNullOrderByIdDesc(booking.getId(), NOTE_TYPES)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No cancellation note exists for booking " + bookingPublicId));
        return renderAndFreeze(doc, false);
    }

    @Override
    @Transactional
    public byte[] renderRefundVoucher(UUID bookingPublicId) {
        Booking booking = loadBooking(bookingPublicId);
        BookingDocument doc = documentRepository
                .findFirstByBookingIdAndDocTypeAndDeletedAtIsNullOrderByIdDesc(
                        booking.getId(), DocumentType.REFUND_VOUCHER)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No refund voucher exists for booking " + bookingPublicId));
        return renderAndFreeze(doc, true);
    }

    /** Serve the frozen bytes; render + freeze them from the snapshot on first fetch. */
    private byte[] renderAndFreeze(BookingDocument doc, boolean voucher) {
        if (doc.getPdfBytes() != null) {
            return doc.getPdfBytes();
        }
        CancellationDocumentModel model = fromJson(doc.getModelSnapshotJson());
        byte[] bytes = voucher ? pdfService.renderRefundVoucher(model) : pdfService.renderNote(model);
        doc.setPdfBytes(bytes);
        documentRepository.save(doc);
        return bytes;
    }

    // ── Model assembly ────────────────────────────────────────────────────────

    private CancellationDocumentModel buildNoteModel(Booking booking, BookingCancellation record,
                                                     CancellationQuote quote, DocumentType docType,
                                                     String number) {
        Customer customer = booking.getCustomerId() != null
                ? customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        booking.getCustomerId(), booking.getTenantId()).orElse(null)
                : null;

        CancellationDocumentModel.CancellationDocumentModelBuilder b = CancellationDocumentModel.builder()
                .documentTitle(docType.title())
                .docNumber(number)
                .originalInvoiceNo(booking.getBookingCode())
                .issuedOn(record.getCancelDate())
                .bookingCode(booking.getBookingCode())
                .bookingDate(booking.getBookingDate())
                .travelDate(booking.getTravelDate())
                .destination(booking.getDestinationSnapshot())
                .cancelledOn(record.getCancelDate())
                .reason(record.getReason())
                .customerName(customer != null ? customer.getName() : booking.getCustomerNameSnapshot())
                .customerCode(customer != null ? customer.getCustomerCode() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .customerEmail(customer != null ? customer.getEmail() : null)
                .customerAddress(customer != null ? customer.getAddress() : null)
                .customerPan(customer != null ? customer.getPanNo() : null)
                .originalTotalPayable(booking.getTotalPayable())
                .baseConsidered(record.getBaseConsidered())
                .chargeBase(record.getFinalChargeBase())
                .gstOnCharge(record.getGstOnCharge())
                .tcsRetained(record.getTcsRetained())
                .totalRetained(record.getTotalRetained())
                .paidAmount(record.getPaidAtCancel())
                .refundDue(record.getRefundDue())
                .refundToCustomer(quote.getRefundToCustomer())
                .customerBalanceOwed(record.getCustomerBalanceOwed())
                .customerOwes(record.isCustomerOwes())
                .brandColor(brandColor);

        applyBranding(b, booking.getTenantId(), booking.getOwnerUserId());
        return b.build();
    }

    /** The customer-safe snapshot a refund voucher renders from — branding frozen, refund fields set. */
    private CancellationDocumentModel buildVoucherModel(Booking booking, BookingCancellation record,
                                                        String number, BigDecimal amount, String method,
                                                        String reference, LocalDate refundDate,
                                                        BigDecimal totalRefundedToDate) {
        Customer customer = booking.getCustomerId() != null
                ? customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        booking.getCustomerId(), booking.getTenantId()).orElse(null)
                : null;

        CancellationDocumentModel.CancellationDocumentModelBuilder b = CancellationDocumentModel.builder()
                .documentTitle(DocumentType.REFUND_VOUCHER.title())
                .docNumber(number)
                // "Against Cancellation Note" on the voucher — the CN this refund settles.
                .originalInvoiceNo(record.getCreditNoteNumber())
                .issuedOn(refundDate)
                .bookingCode(booking.getBookingCode())
                .bookingDate(booking.getBookingDate())
                .travelDate(booking.getTravelDate())
                .destination(booking.getDestinationSnapshot())
                .customerName(customer != null ? customer.getName() : booking.getCustomerNameSnapshot())
                .customerCode(customer != null ? customer.getCustomerCode() : null)
                .customerPhone(customer != null ? customer.getPhone() : null)
                .customerEmail(customer != null ? customer.getEmail() : null)
                .customerAddress(customer != null ? customer.getAddress() : null)
                // Refund-voucher specific
                .refundAmount(amount)
                .refundMethod(method)
                .refundReference(reference)
                .refundDate(refundDate)
                .totalRefundedToDate(totalRefundedToDate)
                .brandColor(brandColor);

        applyBranding(b, booking.getTenantId(), booking.getOwnerUserId());
        return b.build();
    }

    /**
     * Freeze the tenant's branding INTO the snapshot so a reissue reproduces the original document.
     * When {@code ownerUserId} is an active sub-agent, their brand white-labels the identity fields
     * (name/logo/phone/email/colour) at issue time — the legal address/website/GST stay the parent's,
     * as the credit note is issued under the parent tenant's registration.
     */
    private void applyBranding(CancellationDocumentModel.CancellationDocumentModelBuilder b,
                               Long tenantId, Long ownerUserId) {
        String name = defCompanyName, logo = defLogo, phone = defPhone, email = defEmail,
                website = defWebsite, address = defAddress, gst = null;
        Company co = tenantId != null ? companyRepository.findByTenantId(tenantId).orElse(null) : null;
        if (co != null) {
            if (StringUtils.hasText(co.getName()))    name    = co.getName();
            if (StringUtils.hasText(co.getLogoUrl())) logo    = co.getLogoUrl();
            if (StringUtils.hasText(co.getPhone()))   phone   = co.getPhone();
            if (StringUtils.hasText(co.getEmail()))   email   = co.getEmail();
            if (StringUtils.hasText(co.getWebsite())) website = co.getWebsite();
            if (StringUtils.hasText(co.getAddress())) address = co.getAddress();
            gst = co.getGstin();
        }

        var branding = brandingService.resolve(ownerUserId);
        if (branding.isPresent()) {
            var sa = branding.get();
            if (StringUtils.hasText(sa.brandName()))    name  = sa.brandName();
            if (StringUtils.hasText(sa.logoUrl()))      logo  = sa.logoUrl();
            if (StringUtils.hasText(sa.contactPhone())) phone = sa.contactPhone();
            if (StringUtils.hasText(sa.contactEmail())) email = sa.contactEmail();
            if (StringUtils.hasText(sa.brandColor()))   b.brandColor(sa.brandColor());
        }

        b.companyName(name).companyLogoUrl(logo).companyTagline(defTagline).companyPhone(phone)
         .companyEmail(email).companyWebsite(website).companyAddress(address).companyGst(gst);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Booking loadBooking(UUID bookingPublicId) {
        return bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
    }

    private String toJson(CancellationDocumentModel model) {
        try {
            return objectMapper.writeValueAsString(model);
        } catch (Exception ex) {
            throw new BusinessException("Failed to prepare the cancellation document.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CancellationDocumentModel fromJson(String json) {
        try {
            return objectMapper.readValue(json, CancellationDocumentModel.class);
        } catch (Exception ex) {
            throw new BusinessException("Failed to load the cancellation document.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}
