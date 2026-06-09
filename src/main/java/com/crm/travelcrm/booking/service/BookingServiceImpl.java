package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.booking.mapper.BookingMapper;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.booking.specification.BookingSpecification;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.util.BookingCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LogManager.getLogger(BookingServiceImpl.class);

    private static final BigDecimal GST_RATE = new BigDecimal("0.05");
    private static final BigDecimal TCS_RATE = new BigDecimal("0.05");

    private final BookingRepository    bookingRepository;
    private final BookingMapper        bookingMapper;
    private final BookingCodeGenerator bookingCodeGenerator;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO create(CreateBookingRequestDTO request) {
        log.info("Creating new booking for customer: {}", request.getCustomerId());

        Booking booking = bookingMapper.toEntity(request);
        booking.setBookingCode(bookingCodeGenerator.generate());
        booking.setStatus(BookingStatus.PENDING);


        calculateAndApplyFinancials(booking, request.getCustomerAmount(),
                request.getVendorCost(), request.getPaidAmount());

        Booking saved = bookingRepository.save(booking);
        log.info("Booking created successfully with code: {}", saved.getBookingCode());
        return bookingMapper.toResponse(saved);
    }

    // ── Create from Lead ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO createFromLead(Long leadId) {
        log.info("Creating booking from lead id: {}", leadId);

        // Basic shell booking from lead — extend when Lead entity is wired
        Booking booking = Booking.builder()
                .bookingCode(bookingCodeGenerator.generate())
                .leadId(leadId)
                .status(BookingStatus.PENDING)
                .customerAmount(BigDecimal.ZERO)
                .vendorCost(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .gst(BigDecimal.ZERO)
                .tcs(BigDecimal.ZERO)
                .totalPayable(BigDecimal.ZERO)
                .netProfit(BigDecimal.ZERO)
                .paymentStatus(PaymentStatus.UNPAID)
                .bookingDate(LocalDate.now())
                .travelDate(LocalDate.now().plusDays(1))
                .customerNameSnapshot("TBD")
                .destinationSnapshot("TDB")
                .build();

        Booking saved = bookingRepository.save(booking);
        log.info("Booking created from lead. Code: {}", saved.getBookingCode());
        return bookingMapper.toResponse(saved);
    }

    // ── Get by ID ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getById(Long id) {
        return bookingMapper.toResponse(findActiveById(id));
    }

    // ── Get by Code ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getByCode(String code) {
        Booking booking = bookingRepository.findByBookingCodeAndActiveTrue(code)
                .orElseThrow(() -> new BookingNotFoundException(code));
        return bookingMapper.toResponse(booking);
    }

    // ── Get All (Paginated) ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<BookingResponseDTO> getAll(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable  = PageRequest.of(page, size, sort);
        Page<Booking> bookingPage = bookingRepository.findAll(
                BookingSpecification.isActive(), pageable);

        List<BookingResponseDTO> content = bookingPage.getContent()
                .stream()
                .map(bookingMapper::toResponse)
                .toList();

        return PagedApiResponse.of(
                "Bookings fetched successfully",
                content,
                PaginationMeta.from(bookingPage, sortBy, sortDir)
        );
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO update(Long id, UpdateBookingRequestDTO request) {
        log.info("Updating booking id: {}", id);

        Booking booking = findActiveById(id);
        bookingMapper.updateEntity(request, booking);   // applies non-null DTO fields → entity

        // Recalculate only if either financial field was touched
        if (request.getCustomerAmount() != null || request.getVendorCost() != null) {
            calculateAndApplyFinancials(
                    booking,
                    booking.getCustomerAmount(),  // ✅ post-mapper value
                    booking.getVendorCost(),       // ✅ post-mapper value
                    booking.getPaidAmount()        // ✅ paidAmount unchanged in this flow
            );
        }

        return bookingMapper.toResponse(bookingRepository.save(booking));
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updateStatus(Long id, StatusUpdateRequestDTO request) {
        log.info("Updating status for booking id: {} to {}", id, request.getStatus());

        Booking booking = findActiveById(id);
        booking.setStatus(request.getStatus());
        return bookingMapper.toResponse(bookingRepository.save(booking));
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updatePayment(Long id, PaymentUpdateRequestDTO request) {
        log.info("Updating payment for booking id: {}", id);

        Booking booking = findActiveById(id);

        // ✅ Accumulate — this is an incremental payment, not a replacement
        BigDecimal newPaidAmount = booking.getPaidAmount().add(request.getAmount());

        if (newPaidAmount.compareTo(booking.getTotalPayable()) > 0) {
            throw new BusinessException(
                    "Total paid ₹" + newPaidAmount
                            + " exceeds total payable ₹" + booking.getTotalPayable());
        }

        booking.setPaidAmount(newPaidAmount);
        // ✅ REMOVED: setPendingAmount() — @Transient computed via entity getter
        booking.setPaymentStatus(derivePaymentStatus(newPaidAmount, booking.getTotalPayable()));

        return bookingMapper.toResponse(bookingRepository.save(booking));
    }
    // ── Soft Delete ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(Long id) {
        log.info("Soft deleting booking id: {}", id);

        Booking booking = findActiveById(id);

        // Optional: guard against deleting confirmed/completed bookings
        if (booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "Cannot delete a " + booking.getStatus() + " booking. Cancel it first.");
        }

        booking.setActive(Boolean.FALSE);
        booking.setDeletedAt(LocalDateTime.now());   // ✅ stamp the deleted_at column

        bookingRepository.save(booking);
        log.info("Booking soft deleted: {}", booking.getBookingCode());
    }

    // ── Get by Customer ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getByCustomerId(Long customerId) {
        return bookingRepository.findAllByCustomerIdAndActiveTrue(customerId)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> search(String keyword) {
        Specification<Booking> spec = BookingSpecification.isActive()
                .and(BookingSpecification.search(keyword));

        return bookingRepository.findAll(spec)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> filter(
            BookingStatus status,
            PaymentStatus paymentStatus,
            Integer bookingMonth,
            Integer travelMonth,
            Long customerId,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal minAmount,
            BigDecimal maxAmount
    ) {
        Specification<Booking> spec = BookingSpecification.filter(
                status, paymentStatus, bookingMonth, travelMonth,
                customerId, fromDate, toDate, minAmount, maxAmount);

        return bookingRepository.findAll(spec)
                .stream()
                .map(bookingMapper::toResponse)
                .toList();
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingStatsResponseDTO getStats() {
        return BookingStatsResponseDTO.builder()
                .totalBookings(bookingRepository.countByActiveTrue())
                .confirmedBookings(bookingRepository.countByStatusAndActiveTrue(BookingStatus.CONFIRMED))
                .pendingBookings(bookingRepository.countByStatusAndActiveTrue(BookingStatus.PENDING))
                .cancelledBookings(bookingRepository.countByStatusAndActiveTrue(BookingStatus.CANCELLED))
                .completedBookings(bookingRepository.countByStatusAndActiveTrue(BookingStatus.COMPLETED))
                .refundedBookings(bookingRepository.countByStatusAndActiveTrue(BookingStatus.REFUNDED))
                .totalRevenue(bookingRepository.sumTotalRevenue())
                .totalCollected(bookingRepository.sumTotalCollected())
                .totalPending(bookingRepository.sumTotalPending())
                .totalRefundAmount(bookingRepository.sumTotalRefund())
                .netProfit(bookingRepository.sumNetProfit())
                .gstCollected(bookingRepository.sumGstCollected())
                .tcsCollected(bookingRepository.sumTcsCollected())
                .build();
    }

    // ── Page Summary ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingPageSummaryResponseDTO getPageSummary(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Booking> bookingPage = bookingRepository.findAll(
                BookingSpecification.isActive(), pageable);

        List<Booking> bookings = bookingPage.getContent();

        BigDecimal pageRevenue = bookings.stream()
                .map(Booking::getCustomerAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageProfit = bookings.stream()
                .map(Booking::getNetProfit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageGST = bookings.stream()
                .map(Booking::getGst)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pageTCS = bookings.stream()
                .map(Booking::getTcs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal pagePending = bookings.stream()
                .map(Booking::getPendingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return BookingPageSummaryResponseDTO.builder()
                .totalRevenue(pageRevenue)      // ✅ was: pageRevenue
                .netProfit(pageProfit)          // ✅ was: pageProfit
                .gstCollected(pageGST)          // ✅ was: pageGST
                .tcsCollected(pageTCS)          // ✅ was: pageTCS
                .totalPending(pagePending)      // ✅ was: pagePendingAmount
                .build();
    }

    // ── Send Voucher (stub) ──────────────────────────────────────────────────

    @Override
    public void sendVoucher(Long id) {
        Booking booking = findActiveById(id);
        // Email integration will be wired in future sprint
        log.info("Voucher send requested for booking: {}", booking.getBookingCode());
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    private Booking findActiveById(Long id) {
        return bookingRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }

    private void calculateAndApplyFinancials(Booking booking,
                                             BigDecimal customerAmount,
                                             BigDecimal vendorCost,
                                             BigDecimal paidAmount) {
        BigDecimal gst          = customerAmount.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tcs          = customerAmount.multiply(TCS_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPayable = customerAmount.add(gst).add(tcs);
        BigDecimal netProfit    = customerAmount.subtract(vendorCost);

        booking.setGst(gst);
        booking.setTcs(tcs);
        booking.setTotalPayable(totalPayable);
        booking.setPaidAmount(paidAmount);
        booking.setNetProfit(netProfit);
        booking.setPaymentStatus(derivePaymentStatus(paidAmount, totalPayable));
    }

    private PaymentStatus derivePaymentStatus(BigDecimal paidAmount, BigDecimal totalPayable) {
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) return PaymentStatus.UNPAID;
        if (paidAmount.compareTo(totalPayable) >= 0)    return PaymentStatus.PAID;
        return PaymentStatus.PARTIAL;
    }
}