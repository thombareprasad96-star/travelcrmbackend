package com.crm.travelcrm.accounting.invoice.service;

import com.crm.travelcrm.accounting.einvoice.spi.EInvoiceProvider;
import com.crm.travelcrm.accounting.einvoice.spi.IrnResult;
import com.crm.travelcrm.accounting.invoice.dto.IssueInvoiceLineRequest;
import com.crm.travelcrm.accounting.invoice.dto.IssueInvoiceRequest;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceLineResponse;
import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.accounting.invoice.dto.pdf.TaxInvoiceModel;
import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;
import com.crm.travelcrm.accounting.invoice.entity.TaxInvoiceLine;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceStatus;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceLineRepository;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceRepository;
import com.crm.travelcrm.accounting.invoice.util.InvoiceNumberGenerator;
import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import com.crm.travelcrm.accounting.settings.service.AccountingSettingsService;
import com.crm.travelcrm.accounting.support.FinancialYear;
import com.crm.travelcrm.accounting.support.GstStateCodes;
import com.crm.travelcrm.accounting.support.IndianAmountWords;
import com.crm.travelcrm.accounting.tax.dto.GstLineInput;
import com.crm.travelcrm.accounting.tax.dto.GstQuote;
import com.crm.travelcrm.accounting.tax.entity.HsnSacRate;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import com.crm.travelcrm.accounting.tax.service.GstCalculator;
import com.crm.travelcrm.accounting.tax.service.HsnSacRateService;
import com.crm.travelcrm.accounting.tax.service.PlaceOfSupplyResolver;
import com.crm.travelcrm.accounting.tax.service.TcsCalculator;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues, voids, reprints and e-invoices GST invoices. An invoice is a frozen, append-only snapshot of
 * a booking's billed figures — the money of record is computed here (per-line taxable value × the
 * HSN/SAC rate, split by place of supply), never read back from {@code Booking.gst}. The invoice
 * number is minted gap-free inside the issue transaction; the render model is frozen so a reprint is
 * byte-identical.
 */
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private static final Logger log = LogManager.getLogger(InvoiceServiceImpl.class);

    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyRepository companyRepository;
    private final AccountingSettingsService settingsService;
    private final HsnSacRateService hsnSacRateService;
    private final GstCalculator gstCalculator;
    private final TcsCalculator tcsCalculator;
    private final PlaceOfSupplyResolver placeOfSupplyResolver;
    private final InvoiceNumberGenerator numberGenerator;
    private final TaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceLineRepository lineRepository;
    private final InvoicePdfService pdfService;
    private final EInvoiceProvider eInvoiceProvider;
    private final SubAgentBrandingService brandingService;
    private final ObjectMapper objectMapper;

    @Value("${quotation.pdf.company-name:TravelCRM}")     private String defCompanyName;
    @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") private String defTagline;
    @Value("${quotation.pdf.company-phone:}")             private String defPhone;
    @Value("${quotation.pdf.company-email:}")             private String defEmail;
    @Value("${quotation.pdf.company-website:}")           private String defWebsite;
    @Value("${quotation.pdf.company-address:}")           private String defAddress;
    @Value("${quotation.pdf.company-logo-url:}")          private String defLogo;
    @Value("${booking.pdf.brand-color:#B8891F}")          private String brandColor;

    // ── Issue ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaxInvoiceResponse issue(IssueInvoiceRequest req, Long tenantId, String userEmail) {
        Booking booking = loadBooking(req.getBookingPublicId(), tenantId);

        if (invoiceRepository.existsByTenantIdAndBookingIdAndStatusAndDeletedAtIsNull(
                tenantId, booking.getId(), InvoiceStatus.ISSUED)) {
            throw new BusinessException(
                    "An active invoice already exists for this booking. Cancel it before re-issuing.",
                    HttpStatus.CONFLICT);
        }

        AccountingSettings settings = settingsService.loadOrCreate(tenantId);
        Company company = companyRepository.findByTenantId(tenantId).orElse(null);

        InvoiceType type = req.getInvoiceType() != null
                ? req.getInvoiceType() : settings.getGstScheme().defaultInvoiceType();
        boolean applyGst = type.appliesGst();

        if (type == InvoiceType.TAX_INVOICE && !settings.getGstScheme().canIssueTaxInvoice()) {
            throw new BusinessException(
                    "This tenant is not a regular GST dealer, so a Tax Invoice cannot be issued. "
                            + "Choose a Bill of Supply or a Simple Invoice.", HttpStatus.CONFLICT);
        }
        String supplierGstin = company != null ? company.getGstin() : null;
        if (applyGst && !StringUtils.hasText(supplierGstin)) {
            throw new BusinessException(
                    "A supplier GSTIN is required to issue a Tax Invoice. Add it under Company settings.",
                    HttpStatus.CONFLICT);
        }

        LocalDate invoiceDate = req.getInvoiceDate() != null ? req.getInvoiceDate() : LocalDate.now();
        FinancialYear fy = FinancialYear.of(invoiceDate);

        // ── Parties & place of supply ──
        String supplierState = placeOfSupplyResolver.supplierStateCode(
                supplierGstin, company != null ? company.getState() : null);
        Customer customer = booking.getCustomerId() != null
                ? customerRepository.findByIdAndTenantIdAndDeletedAtIsNull(booking.getCustomerId(), tenantId).orElse(null)
                : null;
        String recipientGstin = StringUtils.hasText(req.getRecipientGstin()) ? req.getRecipientGstin().trim() : null;
        String recipientStateName = customer != null ? customer.getState() : null;
        String placeOfSupply = placeOfSupplyResolver.placeOfSupplyCode(
                req.getPlaceOfSupplyState(), recipientGstin, recipientStateName);
        String recipientStateCode = recipientGstin != null
                ? GstStateCodes.fromGstin(recipientGstin) : GstStateCodes.fromStateName(recipientStateName);
        SupplyType supplyType = applyGst
                ? placeOfSupplyResolver.supplyType(supplierState, placeOfSupply) : null;

        // ── Lines + GST ──
        List<GstLineInput> inputs = buildLineInputs(req, booking, tenantId, applyGst);
        GstQuote quote = gstCalculator.calculate(
                inputs, supplyType != null ? supplyType : SupplyType.INTER_STATE, applyGst);

        // ── TCS (overseas tour packages only) ──
        boolean overseas = Boolean.TRUE.equals(req.getOverseasTourPackage());
        boolean applyTcs = overseas
                && (req.getApplyTcs() != null ? req.getApplyTcs() : settings.isAutoTcsOnOverseas());
        BigDecimal tcs = applyTcs
                ? tcsCalculator.compute(quote.getTotalTaxable(), req.getPriorOverseasSpendThisFy())
                : BigDecimal.ZERO;

        // ── Totals + round-off ──
        BigDecimal preRound = quote.getTotalWithGst().add(tcs);
        BigDecimal roundOff = BigDecimal.ZERO;
        BigDecimal invoiceTotal = preRound.setScale(2, RoundingMode.HALF_UP);
        if (settings.isRoundInvoiceTotal()) {
            BigDecimal rounded = preRound.setScale(0, RoundingMode.HALF_UP);
            roundOff = rounded.subtract(preRound).setScale(2, RoundingMode.HALF_UP);
            invoiceTotal = rounded.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal itcCreditable = settings.isInputTaxCreditEligible()
                ? sumItcCreditable(quote) : BigDecimal.ZERO;

        // ── Mint number (gap-free, inside this txn) ──
        String number = numberGenerator.next(tenantId, type.series(), fy);

        TaxInvoice inv = TaxInvoice.builder()
                .tenantId(tenantId)
                .invoiceNumber(number)
                .invoiceType(type)
                .status(InvoiceStatus.ISSUED)
                .invoiceDate(invoiceDate)
                .financialYear(fy.label())
                .bookingId(booking.getId())
                .bookingPublicId(booking.getPublicId())
                .bookingCode(booking.getBookingCode())
                .supplierLegalName(company != null ? company.getName() : defCompanyName)
                .supplierGstin(supplierGstin)
                .supplierStateCode(supplierState)
                .customerId(booking.getCustomerId())
                .recipientName(customer != null ? customer.getName() : booking.getCustomerNameSnapshot())
                .recipientGstin(recipientGstin)
                .recipientStateCode(recipientStateCode)
                .placeOfSupplyCode(placeOfSupply)
                .supplyType(supplyType)
                .reverseCharge(Boolean.TRUE.equals(req.getReverseCharge()))
                .overseasTourPackage(overseas)
                .taxableValue(quote.getTotalTaxable())
                .cgst(quote.getTotalCgst())
                .sgst(quote.getTotalSgst())
                .igst(quote.getTotalIgst())
                .cess(quote.getTotalCess())
                .tcs(tcs)
                .roundOff(roundOff)
                .invoiceTotal(invoiceTotal)
                .itcCreditable(itcCreditable)
                .einvoiceStatus(applyGst ? "PENDING" : "NOT_APPLICABLE")
                .issuedAt(LocalDateTime.now())
                .issuedByEmail(userEmail)
                .build();
        inv = invoiceRepository.save(inv);

        List<TaxInvoiceLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (GstQuote.Line l : quote.getLines()) {
            lines.add(lineRepository.save(TaxInvoiceLine.builder()
                    .tenantId(tenantId)
                    .invoiceId(inv.getId())
                    .lineNo(lineNo++)
                    .description(l.getDescription())
                    .hsnSac(l.getHsnSac())
                    .taxableValue(l.getTaxableValue())
                    .gstRatePct(l.getGstRatePct())
                    .cgstAmt(l.getCgstAmt())
                    .sgstAmt(l.getSgstAmt())
                    .igstAmt(l.getIgstAmt())
                    .cessAmt(l.getCessAmt())
                    .lineTotal(l.getLineTotal())
                    .itcEligible(l.isItcEligible())
                    .serviceItemId(l.getServiceItemId())
                    .build()));
        }

        // Freeze the render model so a reprint reproduces this exact document.
        TaxInvoiceModel model = buildModel(inv, quote, company, customer, booking, tcs, roundOff, invoiceTotal);
        inv.setModelSnapshotJson(toJson(model));
        invoiceRepository.save(inv);

        log.info("Issued {} {} for booking {} (total Rs.{})",
                type, number, booking.getBookingCode(), invoiceTotal);
        return toResponse(inv, lines);
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TaxInvoiceResponse get(UUID publicId, Long tenantId) {
        TaxInvoice inv = require(publicId, tenantId);
        return toResponse(inv, lineRepository.findByInvoiceIdAndTenantIdOrderByLineNoAsc(inv.getId(), tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TaxInvoiceResponse> list(Long tenantId, Pageable pageable) {
        return invoiceRepository.findByTenantIdAndDeletedAtIsNullOrderByIdDesc(tenantId, pageable)
                .map(inv -> toResponse(inv, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxInvoiceResponse> listForBooking(UUID bookingPublicId, Long tenantId) {
        Booking booking = loadBooking(bookingPublicId, tenantId);
        return invoiceRepository
                .findByTenantIdAndBookingIdAndDeletedAtIsNullOrderByIdDesc(tenantId, booking.getId())
                .stream().map(inv -> toResponse(inv, null)).toList();
    }

    // ── Void ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaxInvoiceResponse cancel(UUID publicId, String reason, Long tenantId, String userEmail) {
        TaxInvoice inv = require(publicId, tenantId);
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            throw new BusinessException("This invoice is already cancelled.", HttpStatus.CONFLICT);
        }
        inv.setStatus(InvoiceStatus.CANCELLED);
        inv.setCancelledAt(LocalDateTime.now());
        inv.setCancelReason(reason);
        invoiceRepository.save(inv);
        log.info("Cancelled invoice {} for booking {} by {}", inv.getInvoiceNumber(), inv.getBookingCode(), userEmail);
        return toResponse(inv, lineRepository.findByInvoiceIdAndTenantIdOrderByLineNoAsc(inv.getId(), tenantId));
    }

    // ── PDF (render + freeze on first fetch) ────────────────────────────────────

    @Override
    @Transactional
    public byte[] renderPdf(UUID publicId, Long tenantId) {
        TaxInvoice inv = require(publicId, tenantId);
        if (inv.getPdfBytes() != null && inv.getStatus() == InvoiceStatus.ISSUED) {
            return inv.getPdfBytes();
        }
        TaxInvoiceModel model = fromJson(inv.getModelSnapshotJson());
        // Reflect a later void on the reprint without re-freezing the issued snapshot.
        if (inv.getStatus() == InvoiceStatus.CANCELLED) {
            model.setDocumentTitle(model.getDocumentTitle() + " (CANCELLED)");
        }
        byte[] bytes = pdfService.renderInvoice(model);
        if (inv.getStatus() == InvoiceStatus.ISSUED) {
            inv.setPdfBytes(bytes);
            invoiceRepository.save(inv);
        }
        return bytes;
    }

    // ── e-invoice (IRN) ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaxInvoiceResponse generateEInvoice(UUID publicId, Long tenantId) {
        TaxInvoice inv = require(publicId, tenantId);
        if (inv.getInvoiceType() != InvoiceType.TAX_INVOICE) {
            throw new BusinessException("Only a Tax Invoice can be registered for an IRN.", HttpStatus.CONFLICT);
        }
        if (inv.getStatus() != InvoiceStatus.ISSUED) {
            throw new BusinessException("Only an issued invoice can be e-invoiced.", HttpStatus.CONFLICT);
        }
        IrnResult result = eInvoiceProvider.generate(inv);
        inv.setEinvoiceStatus(result.status().name());
        if (result.status() == IrnResult.Status.GENERATED) {
            inv.setIrn(result.irn());
            inv.setIrnAckNo(result.ackNo());
            inv.setIrnAckDate(result.ackDate());
            inv.setSignedQrData(result.signedQrData());
        }
        invoiceRepository.save(inv);
        log.info("e-invoice for {}: {} ({})", inv.getInvoiceNumber(), result.status(), result.message());
        return toResponse(inv, lineRepository.findByInvoiceIdAndTenantIdOrderByLineNoAsc(inv.getId(), tenantId));
    }

    // ── Line assembly ────────────────────────────────────────────────────────────

    private List<GstLineInput> buildLineInputs(IssueInvoiceRequest req, Booking booking,
                                               Long tenantId, boolean applyGst) {
        HsnSacRate def = hsnSacRateService.resolveDefault(tenantId);
        List<GstLineInput> inputs = new ArrayList<>();

        if (req.getLines() != null && !req.getLines().isEmpty()) {
            for (IssueInvoiceLineRequest l : req.getLines()) {
                HsnSacRate rate = hsnSacRateService.resolveByCodeOrNull(tenantId, l.getHsnSac());
                if (rate == null) rate = def;
                BigDecimal ratePct = l.getGstRatePct() != null ? l.getGstRatePct() : rate.getGstRatePct();
                BigDecimal cessPct = l.getCessPct() != null ? l.getCessPct() : rate.getCessPct();
                inputs.add(GstLineInput.builder()
                        .description(l.getDescription())
                        .hsnSac(StringUtils.hasText(l.getHsnSac()) ? l.getHsnSac() : rate.getCode())
                        .taxableValue(l.getTaxableValue())
                        .gstRatePct(ratePct)
                        .cessPct(cessPct)
                        .itcEligible(rate.isItcEligible())
                        .build());
            }
            return inputs;
        }

        // Auto-assemble from the booking's service items (customer-facing cost as taxable value).
        List<BookingServiceItem> items =
                serviceItemRepository.findByBookingIdAndDeletedAtIsNullOrderByIdAsc(booking.getId());
        BigDecimal itemSum = BigDecimal.ZERO;
        for (BookingServiceItem it : items) {
            if (it.getCost() != null && it.getCost().signum() > 0) itemSum = itemSum.add(it.getCost());
        }

        if (itemSum.signum() > 0) {
            for (BookingServiceItem it : items) {
                if (it.getCost() == null || it.getCost().signum() <= 0) continue;
                inputs.add(GstLineInput.builder()
                        .description(lineDescription(it))
                        .hsnSac(def.getCode())
                        .taxableValue(it.getCost())
                        .gstRatePct(def.getGstRatePct())
                        .cessPct(def.getCessPct())
                        .itcEligible(def.isItcEligible())
                        .serviceItemId(it.getId())
                        .serviceItemPublicId(it.getPublicId())
                        .build());
            }
        } else {
            // Fall back to a single line for the booking's customer amount.
            inputs.add(GstLineInput.builder()
                    .description("Tour package — " + safe(booking.getDestinationSnapshot()))
                    .hsnSac(def.getCode())
                    .taxableValue(nz(booking.getCustomerAmount()))
                    .gstRatePct(def.getGstRatePct())
                    .cessPct(def.getCessPct())
                    .itcEligible(def.isItcEligible())
                    .build());
        }
        return inputs;
    }

    private static String lineDescription(BookingServiceItem it) {
        String type = StringUtils.hasText(it.getServiceType()) ? it.getServiceType() + " — " : "";
        return type + safe(it.getTitle());
    }

    // ── Frozen model ─────────────────────────────────────────────────────────────

    private TaxInvoiceModel buildModel(TaxInvoice inv, GstQuote quote, Company company, Customer customer,
                                       Booking booking, BigDecimal tcs, BigDecimal roundOff, BigDecimal invoiceTotal) {
        List<TaxInvoiceModel.Line> lines = new ArrayList<>();
        int n = 1;
        for (GstQuote.Line l : quote.getLines()) {
            lines.add(TaxInvoiceModel.Line.builder()
                    .lineNo(n++).description(l.getDescription()).hsnSac(l.getHsnSac())
                    .taxableValue(l.getTaxableValue()).gstRatePct(l.getGstRatePct())
                    .cgstAmt(l.getCgstAmt()).sgstAmt(l.getSgstAmt()).igstAmt(l.getIgstAmt())
                    .cessAmt(l.getCessAmt()).lineTotal(l.getLineTotal()).build());
        }

        // Tax summary grouped by GST rate.
        Map<BigDecimal, TaxInvoiceModel.TaxSummaryRow> byRate = new LinkedHashMap<>();
        for (GstQuote.Line l : quote.getLines()) {
            byRate.merge(l.getGstRatePct(),
                    TaxInvoiceModel.TaxSummaryRow.builder()
                            .gstRatePct(l.getGstRatePct()).taxableValue(l.getTaxableValue())
                            .cgstAmt(l.getCgstAmt()).sgstAmt(l.getSgstAmt())
                            .igstAmt(l.getIgstAmt()).cessAmt(l.getCessAmt()).build(),
                    (a, b) -> TaxInvoiceModel.TaxSummaryRow.builder()
                            .gstRatePct(a.getGstRatePct())
                            .taxableValue(a.getTaxableValue().add(b.getTaxableValue()))
                            .cgstAmt(a.getCgstAmt().add(b.getCgstAmt()))
                            .sgstAmt(a.getSgstAmt().add(b.getSgstAmt()))
                            .igstAmt(a.getIgstAmt().add(b.getIgstAmt()))
                            .cessAmt(a.getCessAmt().add(b.getCessAmt())).build());
        }

        boolean intra = inv.getSupplyType() == SupplyType.INTRA_STATE;

        TaxInvoiceModel.TaxInvoiceModelBuilder b = TaxInvoiceModel.builder()
                .documentTitle(inv.getInvoiceType().title())
                .invoiceNumber(inv.getInvoiceNumber())
                .invoiceDate(inv.getInvoiceDate())
                .financialYear(inv.getFinancialYear())
                .reverseCharge(inv.isReverseCharge())
                .appliesGst(inv.getInvoiceType().appliesGst())
                .intraState(intra)
                .placeOfSupply(stateLabel(inv.getPlaceOfSupplyCode()))
                .supplyTypeLabel(inv.getSupplyType() != null
                        ? (intra ? "Intra-State" : "Inter-State") : "—")
                .supplierGstin(inv.getSupplierGstin())
                .supplierStateLabel(stateLabel(inv.getSupplierStateCode()))
                .companyPan(panFromGstin(inv.getSupplierGstin()))
                .recipientName(inv.getRecipientName())
                .recipientAddress(customer != null ? composeAddress(customer) : null)
                .recipientPhone(customer != null ? customer.getPhone() : null)
                .recipientEmail(customer != null ? customer.getEmail() : null)
                .recipientGstin(inv.getRecipientGstin())
                .recipientStateLabel(stateLabel(inv.getRecipientStateCode()))
                .bookingCode(booking.getBookingCode())
                .bookingDate(booking.getBookingDate())
                .travelDate(booking.getTravelDate())
                .destination(booking.getDestinationSnapshot())
                .lines(lines)
                .taxSummary(new ArrayList<>(byRate.values()))
                .taxableValue(inv.getTaxableValue())
                .totalCgst(inv.getCgst()).totalSgst(inv.getSgst())
                .totalIgst(inv.getIgst()).totalCess(inv.getCess())
                .tcs(tcs).overseasTourPackage(inv.isOverseasTourPackage())
                .roundOff(roundOff).invoiceTotal(invoiceTotal)
                .amountInWords(IndianAmountWords.toWords(invoiceTotal))
                .brandColor(brandColor);

        applyBranding(b, company, booking.getOwnerUserId());
        return b.build();
    }

    /** Freeze branding into the model (company profile → defaults → sub-agent white-label overlay). */
    private void applyBranding(TaxInvoiceModel.TaxInvoiceModelBuilder b, Company co, Long ownerUserId) {
        String name = defCompanyName, logo = defLogo, phone = defPhone, email = defEmail,
                website = defWebsite, address = defAddress;
        if (co != null) {
            if (StringUtils.hasText(co.getName()))    name    = co.getName();
            if (StringUtils.hasText(co.getLogoUrl())) logo    = co.getLogoUrl();
            if (StringUtils.hasText(co.getPhone()))   phone   = co.getPhone();
            if (StringUtils.hasText(co.getEmail()))   email   = co.getEmail();
            if (StringUtils.hasText(co.getWebsite())) website = co.getWebsite();
            if (StringUtils.hasText(co.getAddress())) address = co.getAddress();
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
        b.companyName(name).companyLogoUrl(logo).companyTagline(defTagline)
         .companyPhone(phone).companyEmail(email).companyWebsite(website).companyAddress(address);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────────

    private TaxInvoiceResponse toResponse(TaxInvoice inv, List<TaxInvoiceLine> lines) {
        List<TaxInvoiceLineResponse> lineDtos = lines == null ? null : lines.stream()
                .map(l -> TaxInvoiceLineResponse.builder()
                        .lineNo(l.getLineNo()).description(l.getDescription()).hsnSac(l.getHsnSac())
                        .taxableValue(l.getTaxableValue()).gstRatePct(l.getGstRatePct())
                        .cgstAmt(l.getCgstAmt()).sgstAmt(l.getSgstAmt()).igstAmt(l.getIgstAmt())
                        .cessAmt(l.getCessAmt()).lineTotal(l.getLineTotal()).build())
                .toList();
        return TaxInvoiceResponse.builder()
                .publicId(inv.getPublicId())
                .invoiceNumber(inv.getInvoiceNumber())
                .invoiceType(inv.getInvoiceType())
                .invoiceTypeLabel(inv.getInvoiceType().title())
                .status(inv.getStatus())
                .invoiceDate(inv.getInvoiceDate())
                .financialYear(inv.getFinancialYear())
                .bookingPublicId(inv.getBookingPublicId())
                .bookingCode(inv.getBookingCode())
                .supplierGstin(inv.getSupplierGstin())
                .supplierStateCode(inv.getSupplierStateCode())
                .recipientName(inv.getRecipientName())
                .recipientGstin(inv.getRecipientGstin())
                .recipientStateCode(inv.getRecipientStateCode())
                .placeOfSupplyCode(inv.getPlaceOfSupplyCode())
                .supplyType(inv.getSupplyType())
                .reverseCharge(inv.isReverseCharge())
                .overseasTourPackage(inv.isOverseasTourPackage())
                .taxableValue(inv.getTaxableValue())
                .cgst(inv.getCgst()).sgst(inv.getSgst()).igst(inv.getIgst()).cess(inv.getCess())
                .tcs(inv.getTcs()).roundOff(inv.getRoundOff()).invoiceTotal(inv.getInvoiceTotal())
                .einvoiceStatus(inv.getEinvoiceStatus()).irn(inv.getIrn())
                .issuedAt(inv.getIssuedAt()).issuedByEmail(inv.getIssuedByEmail())
                .cancelledAt(inv.getCancelledAt()).cancelReason(inv.getCancelReason())
                .creditNoteNumber(inv.getCreditNoteNumber())
                .lines(lineDtos)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Booking loadBooking(UUID bookingPublicId, Long tenantId) {
        Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(bookingPublicId)
                .orElseThrow(() -> new BookingNotFoundException(bookingPublicId));
        if (!booking.getTenantId().equals(tenantId)) {
            throw new BookingNotFoundException(bookingPublicId);
        }
        return booking;
    }

    private TaxInvoice require(UUID publicId, Long tenantId) {
        return invoiceRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + publicId));
    }

    private static BigDecimal sumItcCreditable(GstQuote quote) {
        BigDecimal sum = BigDecimal.ZERO;
        for (GstQuote.Line l : quote.getLines()) {
            if (l.isItcEligible()) {
                sum = sum.add(l.getCgstAmt()).add(l.getSgstAmt()).add(l.getIgstAmt());
            }
        }
        return sum;
    }

    private String composeAddress(Customer c) {
        StringBuilder sb = new StringBuilder();
        append(sb, c.getAddress());
        append(sb, c.getCity());
        append(sb, c.getState());
        append(sb, c.getPincode());
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (StringUtils.hasText(part)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(part.trim());
        }
    }

    private static String stateLabel(String code) {
        return code == null ? null : code;
    }

    private static String panFromGstin(String gstin) {
        if (gstin != null && gstin.trim().length() >= 12) return gstin.trim().substring(2, 12);
        return null;
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private String toJson(TaxInvoiceModel model) {
        try {
            return objectMapper.writeValueAsString(model);
        } catch (Exception ex) {
            throw new BusinessException("Failed to prepare the invoice document.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private TaxInvoiceModel fromJson(String json) {
        try {
            return objectMapper.readValue(json, TaxInvoiceModel.class);
        } catch (Exception ex) {
            throw new BusinessException("Failed to load the invoice document.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}