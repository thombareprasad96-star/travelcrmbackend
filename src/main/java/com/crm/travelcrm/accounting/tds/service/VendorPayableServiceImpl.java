package com.crm.travelcrm.accounting.tds.service;

import com.crm.travelcrm.accounting.settings.service.AccountingSettingsService;
import com.crm.travelcrm.accounting.support.Percents;
import com.crm.travelcrm.accounting.tds.dto.*;
import com.crm.travelcrm.accounting.tds.entity.VendorBill;
import com.crm.travelcrm.accounting.tds.entity.VendorPayment;
import com.crm.travelcrm.accounting.tds.enums.TdsSection;
import com.crm.travelcrm.accounting.tds.enums.VendorBillStatus;
import com.crm.travelcrm.accounting.tds.repository.VendorBillRepository;
import com.crm.travelcrm.accounting.tds.repository.VendorPaymentRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.entity.BookingServiceItem;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.repository.BookingServiceItemRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.repository.VendorRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * The vendor-payable ledger + TDS engine. Raising a bill snapshots the vendor, computes the TDS to
 * withhold (section rate, uplifted to the 206AA no-PAN rate when the vendor has no PAN) and the net
 * payable. Payments post against the bill; {@code amountPaid} is a {@code @Version}-guarded running
 * total so concurrent disbursements can't corrupt it, and an idempotency key collapses a resubmit.
 */
@Service
@RequiredArgsConstructor
public class VendorPayableServiceImpl implements VendorPayableService {

    private static final Logger log = LogManager.getLogger(VendorPayableServiceImpl.class);

    private final VendorBillRepository billRepository;
    private final VendorPaymentRepository paymentRepository;
    private final AccountingSettingsService accountingSettingsService;
    private final VendorRepository vendorRepository;
    private final BookingRepository bookingRepository;
    private final BookingServiceItemRepository serviceItemRepository;
    private final TdsCalculator tdsCalculator;

    // ── Raise ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorBillResponse raiseBill(RaiseVendorBillRequest req, Long tenantId, String userEmail) {
        Vendor vendor = vendorRepository.findByPublicIdAndTenantId(req.getVendorPublicId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vendor not found: " + req.getVendorPublicId()));

        Long bookingId = null;
        String bookingCode = null;
        Long serviceItemId = null;
        if (req.getBookingPublicId() != null) {
            Booking booking = bookingRepository.findByPublicIdAndDeletedAtIsNull(req.getBookingPublicId())
                    .filter(b -> b.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Booking not found: " + req.getBookingPublicId()));
            bookingId = booking.getId();
            bookingCode = booking.getBookingCode();
            if (req.getServiceItemPublicId() != null) {
                BookingServiceItem item = serviceItemRepository
                        .findByPublicIdAndBookingIdAndDeletedAtIsNull(req.getServiceItemPublicId(), bookingId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Service line not found on this booking: " + req.getServiceItemPublicId()));
                serviceItemId = item.getId();
            }
        }

        BigDecimal gross = money(req.getGrossAmount());
        BigDecimal gstInput = money(req.getGstInput());
        BigDecimal tdsBase = req.getTdsBase() != null ? money(req.getTdsBase())
                : gross.subtract(gstInput).max(BigDecimal.ZERO);

        String pan = vendor.getPanNumber();
        boolean hasPan = StringUtils.hasText(pan);
        // Section rates are the TENANT's. 206AA (no PAN ⇒ the higher rate) is still enforced inside
        // the calculator regardless of what they configured — that is statute, not preference.
        var taxSettings = accountingSettingsService.loadOrCreate(tenantId);
        TdsResult tds = tdsCalculator.compute(tdsBase, req.getTdsSection(), hasPan,
                new TdsCalculator.TdsRates(
                        Percents.toFraction(taxSettings.getTds194cPct()),
                        Percents.toFraction(taxSettings.getTds194hPct()),
                        Percents.toFraction(taxSettings.getTds194jPct()),
                        Percents.toFraction(taxSettings.getTdsNoPanPct())));
        BigDecimal netPayable = gross.subtract(tds.amount()).max(BigDecimal.ZERO);

        VendorBill bill = VendorBill.builder()
                .tenantId(tenantId)
                .vendorId(vendor.getId())
                .vendorPublicId(vendor.getPublicId())
                .vendorNameSnapshot(vendor.getVendorName())
                .panSnapshot(pan)
                .gstinSnapshot(vendor.getGstNumber())
                .bookingId(bookingId)
                .bookingCode(bookingCode)
                .serviceItemId(serviceItemId)
                .billNumber(req.getBillNumber())
                .billDate(req.getBillDate() != null ? req.getBillDate() : LocalDate.now())
                .description(req.getDescription())
                .grossAmount(gross)
                .gstInput(gstInput)
                .tdsBase(tdsBase)
                .tdsSection(req.getTdsSection())
                .tdsRatePct(tds.ratePct())
                .tdsAmount(tds.amount())
                .netPayable(netPayable)
                .amountPaid(BigDecimal.ZERO)
                .status(VendorBillStatus.UNPAID)
                .build();
        bill = billRepository.save(bill);
        log.info("Raised vendor bill {} for {} (gross Rs.{}, TDS Rs.{}, net Rs.{})",
                bill.getPublicId(), vendor.getVendorName(), gross, tds.amount(), netPayable);
        return toResponse(bill, List.of());
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<VendorBillResponse> list(Long tenantId, Pageable pageable) {
        return billRepository.findByTenantIdAndDeletedAtIsNullOrderByIdDesc(tenantId, pageable)
                .map(b -> toResponse(b, null));
    }

    @Override
    @Transactional(readOnly = true)
    public VendorBillResponse get(UUID publicId, Long tenantId) {
        VendorBill bill = require(publicId, tenantId);
        return toResponse(bill,
                paymentRepository.findByVendorBillIdAndTenantIdAndDeletedAtIsNullOrderByIdAsc(bill.getId(), tenantId));
    }

    // ── Pay ────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorBillResponse recordPayment(UUID billPublicId, RecordVendorPaymentRequest req,
                                            Long tenantId, String userEmail) {
        VendorBill bill = require(billPublicId, tenantId);
        if (bill.getStatus() == VendorBillStatus.CANCELLED) {
            throw new BusinessException("This bill is cancelled; no payment can be recorded.", HttpStatus.CONFLICT);
        }

        // Idempotency: a resubmit with the same key returns the original, disbursing nothing.
        if (StringUtils.hasText(req.getIdempotencyKey())) {
            var existing = paymentRepository.findByTenantIdAndVendorBillIdAndIdempotencyKeyAndDeletedAtIsNull(
                    tenantId, bill.getId(), req.getIdempotencyKey().trim());
            if (existing.isPresent()) {
                return toResponse(bill,
                        paymentRepository.findByVendorBillIdAndTenantIdAndDeletedAtIsNullOrderByIdAsc(bill.getId(), tenantId));
            }
        }

        BigDecimal amount = money(req.getAmount());
        if (amount.signum() <= 0) {
            throw new BusinessException("Payment amount must be greater than zero.", HttpStatus.BAD_REQUEST);
        }
        BigDecimal newPaid = money(bill.getAmountPaid()).add(amount);
        if (newPaid.compareTo(bill.getNetPayable()) > 0) {
            throw new BusinessException("Payment exceeds the net payable of this bill.", HttpStatus.CONFLICT);
        }

        VendorPayment payment = VendorPayment.builder()
                .tenantId(tenantId)
                .vendorBillId(bill.getId())
                .vendorId(bill.getVendorId())
                .amount(amount)
                .tdsWithheld(BigDecimal.ZERO)
                .paymentDate(req.getPaymentDate() != null ? req.getPaymentDate() : LocalDate.now())
                .paymentMethod(req.getPaymentMethod())
                .reference(req.getReference())
                .notes(req.getNotes())
                .idempotencyKey(StringUtils.hasText(req.getIdempotencyKey()) ? req.getIdempotencyKey().trim() : null)
                .build();
        paymentRepository.save(payment);

        bill.setAmountPaid(newPaid);   // @Version serialises concurrent disbursements
        bill.setStatus(newPaid.compareTo(bill.getNetPayable()) >= 0
                ? VendorBillStatus.PAID
                : (newPaid.signum() > 0 ? VendorBillStatus.PARTIALLY_PAID : VendorBillStatus.UNPAID));
        billRepository.save(bill);

        log.info("Recorded Rs.{} against vendor bill {} ({} of Rs.{})",
                amount, bill.getPublicId(), newPaid, bill.getNetPayable());
        return toResponse(bill,
                paymentRepository.findByVendorBillIdAndTenantIdAndDeletedAtIsNullOrderByIdAsc(bill.getId(), tenantId));
    }

    // ── Cancel ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorBillResponse cancelBill(UUID publicId, String reason, Long tenantId, String userEmail) {
        VendorBill bill = require(publicId, tenantId);
        if (bill.getStatus() == VendorBillStatus.CANCELLED) {
            throw new BusinessException("This bill is already cancelled.", HttpStatus.CONFLICT);
        }
        if (money(bill.getAmountPaid()).signum() > 0) {
            throw new BusinessException(
                    "This bill has payments recorded and cannot be cancelled.", HttpStatus.CONFLICT);
        }
        bill.setStatus(VendorBillStatus.CANCELLED);
        bill.setCancelledAt(LocalDateTime.now());
        bill.setCancelReason(reason);
        billRepository.save(bill);
        return toResponse(bill, List.of());
    }

    // ── TDS summary ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TdsSummaryRow> tdsSummary(Long tenantId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        Map<TdsSection, long[]> counts = new LinkedHashMap<>();
        Map<TdsSection, BigDecimal[]> sums = new LinkedHashMap<>();
        for (VendorBill b : billRepository.findByTenantIdAndBillDateBetweenAndDeletedAtIsNull(tenantId, start, end)) {
            if (b.getTdsSection() == null || b.getStatus() == VendorBillStatus.CANCELLED) continue;
            TdsSection s = b.getTdsSection();
            counts.computeIfAbsent(s, k -> new long[]{0})[0]++;
            BigDecimal[] agg = sums.computeIfAbsent(s, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            agg[0] = agg[0].add(money(b.getTdsBase()));
            agg[1] = agg[1].add(money(b.getTdsAmount()));
        }

        List<TdsSummaryRow> rows = new ArrayList<>();
        for (Map.Entry<TdsSection, BigDecimal[]> e : sums.entrySet()) {
            rows.add(TdsSummaryRow.builder()
                    .section(e.getKey().code())
                    .sectionLabel(e.getKey().label())
                    .billCount(counts.get(e.getKey())[0])
                    .totalBase(e.getValue()[0])
                    .totalTds(e.getValue()[1])
                    .build());
        }
        return rows;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private VendorBill require(UUID publicId, Long tenantId) {
        return billRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor bill not found: " + publicId));
    }

    private VendorBillResponse toResponse(VendorBill b, List<VendorPayment> payments) {
        List<VendorPaymentResponse> paymentDtos = payments == null ? null : payments.stream()
                .map(p -> VendorPaymentResponse.builder()
                        .publicId(p.getPublicId())
                        .amount(p.getAmount())
                        .tdsWithheld(p.getTdsWithheld())
                        .paymentDate(p.getPaymentDate())
                        .paymentMethod(p.getPaymentMethod())
                        .reference(p.getReference())
                        .notes(p.getNotes())
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();
        return VendorBillResponse.builder()
                .publicId(b.getPublicId())
                .vendorPublicId(b.getVendorPublicId())
                .vendorName(b.getVendorNameSnapshot())
                .panSnapshot(b.getPanSnapshot())
                .gstinSnapshot(b.getGstinSnapshot())
                .bookingCode(b.getBookingCode())
                .billNumber(b.getBillNumber())
                .billDate(b.getBillDate())
                .description(b.getDescription())
                .grossAmount(b.getGrossAmount())
                .gstInput(b.getGstInput())
                .tdsBase(b.getTdsBase())
                .tdsSection(b.getTdsSection())
                .tdsSectionLabel(b.getTdsSection() != null ? b.getTdsSection().label() : null)
                .tdsRatePct(b.getTdsRatePct())
                .tdsAmount(b.getTdsAmount())
                .netPayable(b.getNetPayable())
                .amountPaid(b.getAmountPaid())
                .balancePayable(b.getBalancePayable())
                .status(b.getStatus())
                .cancelledAt(b.getCancelledAt())
                .cancelReason(b.getCancelReason())
                .payments(paymentDtos)
                .build();
    }

    private static BigDecimal money(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}