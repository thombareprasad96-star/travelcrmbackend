package com.crm.travelcrm.customer.service;

import com.crm.travelcrm.booking.dto.CustomerBookingMetrics;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.customer.dto.request.CreateCustomerRequest;
import com.crm.travelcrm.customer.dto.request.StatusUpdateRequest;
import com.crm.travelcrm.customer.dto.request.TierUpdateRequest;
import com.crm.travelcrm.customer.dto.request.UpdateCustomerRequest;
import com.crm.travelcrm.customer.dto.response.CustomerBookingResponse;
import com.crm.travelcrm.customer.dto.response.CustomerResponse;
import com.crm.travelcrm.customer.dto.response.CustomerStatsResponse;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.enums.CustomerStatus;
import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.crm.travelcrm.customer.exception.CustomerNotFoundException;
import com.crm.travelcrm.customer.exception.DuplicateCustomerException;
import com.crm.travelcrm.customer.mapper.CustomerMapper;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import com.crm.travelcrm.customer.specification.CustomerSpecification;
import com.crm.travelcrm.customer.util.CustomerCodeGenerator;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.domain.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default {@link CustomerService} implementation.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>Tenant safety</b> — {@code tenantId} is read from {@code TenantContext}
 *       and pushed into every query, independent of the Hibernate filter.</li>
 *   <li><b>Soft delete</b> — deletes flip {@code deletedAt}; nothing is physically
 *       removed and every read excludes deleted rows.</li>
 *   <li><b>No N+1</b> — list endpoints compute booking metrics with a single
 *       grouped query and join them in memory by customer id.</li>
 *   <li><b>Separation</b> — DTO conversion lives in {@link CustomerMapper}; this
 *       class owns business rules and enrichment only.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;
    private final CustomerMapper customerMapper;
    private final CustomerCodeGenerator customerCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;

    // ── Create ───────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        Long tenantId = currentTenantId();
        String phone = normalizePhone(request.getPhone());

        log.info("Creating customer | phone: {} | tenantId: {}", phone, tenantId);

        if (customerRepository.existsByPhoneAndTenantIdAndDeletedAtIsNull(phone, tenantId)) {
            throw new DuplicateCustomerException(
                    "A customer already exists with phone: " + request.getPhone());
        }

        Customer customer = customerMapper.toEntity(request);
        customer.setPhone(phone);
        customer.setEmail(normalizeEmail(request.getEmail()));
        customer.setTenantId(tenantId);
        customer.setCustomerCode(customerCodeGenerator.generate(tenantId));
        applyDefaults(customer);

        Customer saved = customerRepository.save(customer);
        log.info("Customer created | code: {} | publicId: {}", saved.getCustomerCode(), saved.getPublicId());

        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(NotificationType.CUSTOMER_CREATED.name())
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("New Customer: " + saved.getName())
                .message("Customer " + saved.getName() + " (" + saved.getCustomerCode() + ") was added")
                .referenceType("CUSTOMER")
                .referencePublicId(saved.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());

        // A brand-new customer has no bookings yet — skip the metrics query.
        return enrichWithMetrics(saved, Map.of());
    }

    // ── Read ──────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CustomerResponse> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = currentTenantId();

        Pageable pageable = PageRequest.of(page, size, buildSort(sortBy, sortDir));
        Page<Customer> customerPage =
                customerRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);

        List<CustomerResponse> content = enrichList(customerPage.getContent(), tenantId);
        log.debug("Fetched {} customers (page {}) for tenantId: {}", content.size(), page, tenantId);

        return PagedApiResponse.of(
                "Customers fetched successfully",
                content,
                PaginationMeta.from(customerPage, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse getById(UUID publicId) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);
        return enrichSingle(customer, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponse searchByPhone(String phone) {
        Long tenantId = currentTenantId();
        String normalized = normalizePhone(phone);

        Customer customer = customerRepository
                .findByPhoneAndTenantIdAndDeletedAtIsNull(normalized, tenantId)
                .orElseThrow(() -> new CustomerNotFoundException(
                        "No customer found with phone: " + phone));

        return enrichSingle(customer, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> searchByName(String name) {
        Long tenantId = currentTenantId();
        if (!StringUtils.hasText(name)) {
            return Collections.emptyList();
        }
        List<Customer> matches = customerRepository
                .findByTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCase(tenantId, name.trim());
        return enrichList(matches, tenantId);
    }

    // ── Update ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CustomerResponse update(UUID publicId, UpdateCustomerRequest request) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);

        String newPhone = normalizePhone(request.getPhone());
        if (customerRepository.existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
                newPhone, tenantId, publicId)) {
            throw new DuplicateCustomerException(
                    "Another customer already uses phone: " + request.getPhone());
        }

        // Mutate the managed entity in place (null fields are ignored by the mapper).
        customerMapper.updateEntity(request, customer);
        customer.setPhone(newPhone);
        if (request.getEmail() != null) {
            customer.setEmail(normalizeEmail(request.getEmail()));
        }
        applyDefaults(customer);

        Customer updated = customerRepository.save(customer);
        log.info("Customer updated | publicId: {} | tenantId: {}", publicId, tenantId);
        return enrichSingle(updated, tenantId);
    }

    @Override
    @Transactional
    public CustomerResponse updateStatus(UUID publicId, StatusUpdateRequest request) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);
        customer.setStatus(request.getStatus());
        Customer saved = customerRepository.save(customer);
        log.info("Customer status set to {} | publicId: {}", request.getStatus(), publicId);
        return enrichSingle(saved, tenantId);
    }

    @Override
    @Transactional
    public CustomerResponse updateTier(UUID publicId, TierUpdateRequest request) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);
        customer.setTier(request.getTier());
        Customer saved = customerRepository.save(customer);
        log.info("Customer tier set to {} | publicId: {}", request.getTier(), publicId);
        return enrichSingle(saved, tenantId);
    }

    // ── Delete (soft) ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);
        customer.softDelete(currentUserEmail());
        customerRepository.save(customer);
        log.info("Customer soft-deleted | publicId: {} | tenantId: {}", publicId, tenantId);
    }

    // ── Filter ────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponse> filter(String status, String type, String tier) {
        Long tenantId = currentTenantId();

        Specification<Customer> spec = CustomerSpecification.activeForTenant(tenantId)
                .and(CustomerSpecification.hasStatus(parseStatus(status)))
                .and(CustomerSpecification.hasType(parseType(type)))
                .and(CustomerSpecification.hasTier(parseTier(tier)));

        List<Customer> matches = customerRepository.findAll(spec);
        log.debug("Filter matched {} customers for tenantId: {} (status={}, type={}, tier={})",
                matches.size(), tenantId, status, type, tier);
        return enrichList(matches, tenantId);
    }

    // ── Booking history ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CustomerBookingResponse> getBookingHistory(UUID publicId) {
        Long tenantId = currentTenantId();
        Customer customer = findOrThrow(publicId, tenantId);

        return bookingRepository
                .findAllByCustomerIdAndActiveTrueOrderByBookingDateDesc(customer.getId())
                .stream()
                .map(this::toBookingResponse)
                .toList();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CustomerStatsResponse getStats() {
        Long tenantId = currentTenantId();

        return CustomerStatsResponse.builder()
                .total(customerRepository.countByTenantIdAndDeletedAtIsNull(tenantId))
                .active(customerRepository.countByTenantIdAndDeletedAtIsNullAndStatus(tenantId, CustomerStatus.ACTIVE))
                .inactive(customerRepository.countByTenantIdAndDeletedAtIsNullAndStatus(tenantId, CustomerStatus.INACTIVE))
                .blocked(customerRepository.countByTenantIdAndDeletedAtIsNullAndStatus(tenantId, CustomerStatus.BLOCKED))
                .vip(customerRepository.countByTenantIdAndDeletedAtIsNullAndType(tenantId, CustomerType.VIP))
                .corporate(customerRepository.countByTenantIdAndDeletedAtIsNullAndType(tenantId, CustomerType.CORPORATE))
                .regular(customerRepository.countByTenantIdAndDeletedAtIsNullAndType(tenantId, CustomerType.REGULAR))
                .totalRevenue(bookingRepository.sumRevenueByTenant(tenantId))
                .totalBookings(bookingRepository.countByTenant(tenantId))
                .repeatCustomers(bookingRepository.findRepeatCustomerIds(tenantId).size())
                .build();
    }

    // ── CSV export ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        Long tenantId = currentTenantId();
        List<Customer> customers = customerRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);
        Map<Long, CustomerBookingMetrics> metrics = loadMetrics(customers, tenantId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PrintWriter writer = new PrintWriter(out, false, StandardCharsets.UTF_8)) {
            writer.println("Customer Code,Name,Phone,Email,Type,Tier,Status,City,State,"
                    + "Bookings,Total Spent,Last Booking");

            for (Customer c : customers) {
                CustomerBookingMetrics m = metrics.get(c.getId());
                long bookings = m != null ? m.getBookingCount() : 0L;
                BigDecimal spent = m != null && m.getTotalSpent() != null ? m.getTotalSpent() : BigDecimal.ZERO;
                LocalDate last = m != null ? m.getLastBookingDate() : null;

                writer.println(String.join(",",
                        csv(c.getCustomerCode()),
                        csv(c.getName()),
                        csv(c.getPhone()),
                        csv(c.getEmail()),
                        csv(displayName(c.getType())),
                        csv(displayName(c.getTier())),
                        csv(displayName(c.getStatus())),
                        csv(c.getCity()),
                        csv(c.getState()),
                        String.valueOf(bookings),
                        spent.toPlainString(),
                        last != null ? CSV_DATE.format(last) : ""));
            }
        }
        log.info("Exported {} customers to CSV for tenantId: {}", customers.size(), tenantId);
        return out.toByteArray();
    }

    // ── Enrichment helpers ────────────────────────────────────────────────────────

    /** Maps a batch of customers and joins their booking metrics with one query. */
    private List<CustomerResponse> enrichList(List<Customer> customers, Long tenantId) {
        if (customers.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, CustomerBookingMetrics> metrics = loadMetrics(customers, tenantId);
        return customers.stream()
                .map(c -> enrichWithMetrics(c, metrics))
                .toList();
    }

    /** Convenience for single-entity reads — one customer, one metrics lookup. */
    private CustomerResponse enrichSingle(Customer customer, Long tenantId) {
        Map<Long, CustomerBookingMetrics> metrics = loadMetrics(List.of(customer), tenantId);
        return enrichWithMetrics(customer, metrics);
    }

    private Map<Long, CustomerBookingMetrics> loadMetrics(List<Customer> customers, Long tenantId) {
        List<Long> ids = customers.stream().map(Customer::getId).toList();
        return bookingRepository.findMetricsByCustomerIds(tenantId, ids).stream()
                .collect(Collectors.toMap(CustomerBookingMetrics::getCustomerId, Function.identity()));
    }

    private CustomerResponse enrichWithMetrics(Customer customer, Map<Long, CustomerBookingMetrics> metrics) {
        CustomerBookingMetrics m = metrics.get(customer.getId());
        return customerMapper.toResponse(customer).toBuilder()
                .bookings(m != null ? m.getBookingCount() : 0L)
                .spent(m != null && m.getTotalSpent() != null ? m.getTotalSpent() : BigDecimal.ZERO)
                .lastBooking(m != null ? m.getLastBookingDate() : null)
                .build();
    }

    private CustomerBookingResponse toBookingResponse(Booking booking) {
        return CustomerBookingResponse.builder()
                .id(booking.getPublicId())
                .code(booking.getBookingCode())
                .dest(booking.getDestinationSnapshot())
                .date(booking.getBookingDate())
                .amt(booking.getCustomerAmount())
                .status(booking.getStatus())
                .build();
    }

    // ── Generic helpers ───────────────────────────────────────────────────────────

    private Customer findOrThrow(UUID publicId, Long tenantId) {
        return customerRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new CustomerNotFoundException(publicId));
    }

    /** Fill in business defaults so the DB invariants (NOT NULL enums) always hold. */
    private void applyDefaults(Customer customer) {
        if (customer.getType() == null)   customer.setType(CustomerType.INDIVIDUAL);
        if (customer.getTier() == null)   customer.setTier(LoyaltyTier.BRONZE);
        if (customer.getStatus() == null) customer.setStatus(CustomerStatus.ACTIVE);
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter ran and the JWT carries a tenantId claim.");
        }
        return tenantId;
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    private String normalizePhone(String phone) {
        return phone == null ? null : phone.trim();
    }

    private String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase() : null;
    }

    private CustomerStatus parseStatus(String value) {
        return StringUtils.hasText(value) ? CustomerStatus.fromValue(value) : null;
    }

    private CustomerType parseType(String value) {
        return StringUtils.hasText(value) ? CustomerType.fromValue(value) : null;
    }

    private LoyaltyTier parseTier(String value) {
        return StringUtils.hasText(value) ? LoyaltyTier.fromValue(value) : null;
    }

    private String displayName(CustomerType type)   { return type   == null ? "" : type.getDisplayName(); }
    private String displayName(LoyaltyTier tier)     { return tier   == null ? "" : tier.getDisplayName(); }
    private String displayName(CustomerStatus status){ return status == null ? "" : status.getDisplayName(); }

    /** Minimal RFC-4180 CSV escaping for a single field. */
    private String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        boolean mustQuote = escaped.contains(",") || escaped.contains("\n")
                || escaped.contains("\r") || escaped.contains("\"");
        return mustQuote ? "\"" + escaped + "\"" : escaped;
    }

    private Sort buildSort(String sortBy, String sortDir) {
        String property = StringUtils.hasText(sortBy) ? sortBy : "createdAt";
        return "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(property).ascending()
                : Sort.by(property).descending();
    }
}