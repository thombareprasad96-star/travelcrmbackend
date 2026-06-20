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
import com.crm.travelcrm.booking.util.BookingCodeGenerator;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.domain.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final Logger log = LogManager.getLogger(BookingServiceImpl.class);

    private static final BigDecimal GST_RATE = new BigDecimal("0.05");
    private static final BigDecimal TCS_RATE = new BigDecimal("0.05");

    private final BookingRepository    bookingRepository;
    private final BookingMapper        bookingMapper;
    private final BookingCodeGenerator bookingCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerRepository   customerRepository;
    private final LeadRepository       leadRepository;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO create(CreateBookingRequestDTO request) {
        log.info("Creating new booking for customer: {}", request.getCustomerId());

        Long tenantId = requireTenantId();

        Booking booking = bookingMapper.toEntity(request);
        booking.setTenantId(tenantId);
        booking.setBookingCode(bookingCodeGenerator.generate());
        booking.setStatus(BookingStatus.PENDING);

        // Validate cross-aggregate references (no DB FK) and snapshot the resolved values.
        Customer customer = customerRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(request.getCustomerId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found: " + request.getCustomerId()));
        booking.setCustomerId(customer.getId());
        booking.setCustomerNameSnapshot(customer.getName());

        // Destination is sent as a free-text name (no id); snapshot it so the NOT NULL holds.
        booking.setDestinationSnapshot(request.getDestination());

        if (request.getLeadId() != null) {
            if (!leadRepository.existsByIdAndTenantIdAndDeletedAtIsNull(request.getLeadId(), tenantId)) {
                throw new ResourceNotFoundException("Lead not found: " + request.getLeadId());
            }
            booking.setLeadId(request.getLeadId());
        }

        calculateAndApplyFinancials(booking, request.getCustomerAmount(),
                request.getVendorCost(), request.getPaidAmount());

        Booking saved = bookingRepository.save(booking);
        log.info("Booking created successfully with code: {}", saved.getBookingCode());
        publishBookingEvent(NotificationType.BOOKING_CREATED, saved,
                "New Booking: " + saved.getBookingCode(),
                "A new booking " + saved.getBookingCode() + " was created");
        return bookingMapper.toResponse(saved);
    }

    // ── Create from Lead ─────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO createFromLead(Long leadId) {
        log.info("Creating booking from lead id: {}", leadId);

        // Basic shell booking from lead — extend when Lead entity is wired
        Booking booking = Booking.builder()
                .tenantId(requireTenantId())
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
    public BookingResponseDTO getById(UUID publicId) {
        return bookingMapper.toResponse(findActiveByPublicId(publicId));
    }

    // ── Get by Code ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDTO getByCode(String code) {
        Booking booking = bookingRepository.findByBookingCodeAndDeletedAtIsNull(code)
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
    public BookingResponseDTO update(UUID publicId, UpdateBookingRequestDTO request) {
        log.info("Updating booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);
        bookingMapper.updateEntity(request, booking);   // applies non-null DTO fields → entity

        // Destination is a free-text snapshot (mapper ignores it) — apply it explicitly.
        if (request.getDestination() != null) {
            booking.setDestinationSnapshot(request.getDestination());
        }

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
    public BookingResponseDTO updateStatus(UUID publicId, StatusUpdateRequestDTO request) {
        log.info("Updating status for booking publicId: {} to {}", publicId, request.getStatus());

        Booking booking = findActiveByPublicId(publicId);
        booking.setStatus(request.getStatus());
        Booking saved = bookingRepository.save(booking);
        publishBookingEvent(statusEventType(saved.getStatus()), saved,
                "Booking " + saved.getStatus() + ": " + saved.getBookingCode(),
                "Booking " + saved.getBookingCode() + " status changed to " + saved.getStatus());
        return bookingMapper.toResponse(saved);
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public BookingResponseDTO updatePayment(UUID publicId, PaymentUpdateRequestDTO request) {
        log.info("Updating payment for booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);

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

        Booking saved = bookingRepository.save(booking);
        publishBookingEvent(NotificationType.BOOKING_PAYMENT_UPDATED, saved,
                "Payment updated: " + saved.getBookingCode(),
                "₹" + request.getAmount() + " received for booking " + saved.getBookingCode()
                        + " (" + saved.getPaymentStatus() + ")");
        return bookingMapper.toResponse(saved);
    }
    // ── Soft Delete ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        log.info("Soft deleting booking publicId: {}", publicId);

        Booking booking = findActiveByPublicId(publicId);

        if (booking.getStatus() == BookingStatus.CONFIRMED
                || booking.getStatus() == BookingStatus.COMPLETED) {
            throw new BusinessException(
                    "Cannot delete a " + booking.getStatus() + " booking. Cancel it first.");
        }

        booking.softDelete(currentUserEmail());

        bookingRepository.save(booking);
        log.info("Booking soft deleted: {}", booking.getBookingCode());
    }

    // ── Get by Customer ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<BookingResponseDTO> getByCustomerId(Long customerId) {
        return bookingRepository.findAllByCustomerIdAndDeletedAtIsNull(customerId)
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
                .totalBookings(bookingRepository.countByDeletedAtIsNull())
                .confirmedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CONFIRMED))
                .pendingBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.PENDING))
                .cancelledBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.CANCELLED))
                .completedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.COMPLETED))
                .refundedBookings(bookingRepository.countByStatusAndDeletedAtIsNull(BookingStatus.REFUNDED))
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
    @Transactional(readOnly = true)
    public void sendVoucher(UUID publicId) {
        Booking booking = findActiveByPublicId(publicId);
        // Email integration will be wired in future sprint
        log.info("Voucher send requested for booking: {}", booking.getBookingCode());
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    private Booking findActiveByPublicId(UUID publicId) {
        return bookingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BookingNotFoundException(publicId));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — used as the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    private static NotificationType statusEventType(BookingStatus status) {
        return switch (status) {
            case CONFIRMED -> NotificationType.BOOKING_CONFIRMED;
            case CANCELLED -> NotificationType.BOOKING_CANCELLED;
            default        -> NotificationType.BOOKING_STATUS_CHANGED;
        };
    }

    /** Fan-out to tenant admins (recipients resolved in the notification module), actor excluded. */
    private void publishBookingEvent(NotificationType type, Booking booking, String title, String message) {
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(type.name())
                .tenantId(booking.getTenantId())
                .actorUserId(currentUserId())
                .title(title)
                .message(message)
                .referenceType("BOOKING")
                .referencePublicId(booking.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
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