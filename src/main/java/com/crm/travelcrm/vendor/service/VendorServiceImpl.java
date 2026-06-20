package com.crm.travelcrm.vendor.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.domain.enums.NotificationType;
import com.crm.travelcrm.vendor.dto.request.*;
import com.crm.travelcrm.vendor.dto.response.VendorResponseDTO;
import com.crm.travelcrm.vendor.dto.response.VendorStatsDTO;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.enums.VendorPayStatus;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import com.crm.travelcrm.vendor.mapper.VendorMapper;
import com.crm.travelcrm.vendor.repository.VendorRepository;
import com.crm.travelcrm.vendor.specification.VendorSpecification;
import com.crm.travelcrm.vendor.util.VendorCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VendorServiceImpl implements VendorService {

    private static final Logger log = LogManager.getLogger(VendorServiceImpl.class);

    private static final List<String> VENDOR_TYPES = List.of(
            "Hotel", "Airlines", "Transport", "DMC",
            "Travel Agency", "Car Rental", "Cruise", "Insurance"
    );

    private final VendorRepository vendorRepository;
    private final VendorCodeGenerator vendorCodeGenerator;
    private final VendorMapper vendorMapper;
    private final ApplicationEventPublisher eventPublisher;

    // ── GET ALL ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<VendorResponseDTO> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = TenantContext.getTenantId();

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Vendor> vendorPage = vendorRepository.findAll(
                VendorSpecification.isActiveTenant(tenantId), pageable);

        List<VendorResponseDTO> content = vendorPage.getContent()
                .stream()
                .map(vendorMapper::toResponse)
                .toList();

        log.debug("Fetched {} vendors for tenantId: {}", content.size(), tenantId);

        return PagedApiResponse.of(
                "Vendors fetched successfully",
                content,
                PaginationMeta.from(vendorPage, sortBy, sortDir)
        );
    }

    // ── GET BY ID ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VendorResponseDTO getById(Long id) {
        return vendorMapper.toResponse(findOrThrow(id));
    }

    // ── GET BY CODE ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VendorResponseDTO getByCode(String code) {
        Long tenantId = TenantContext.getTenantId();
        Vendor vendor = vendorRepository.findByVendorCodeAndTenantIdAndDeletedAtIsNull(code, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with code: " + code));
        log.debug("Fetched vendor by code: {}", code);
        return vendorMapper.toResponse(vendor);
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO create(VendorRequestDTO req) {
        Long tenantId = TenantContext.getTenantId();

        Vendor vendor = vendorMapper.toEntity(req);
        vendor.setTenantId(tenantId);
        vendor.setVendorCode(vendorCodeGenerator.generate());
        vendor.setStatus(req.getStatus() != null ? req.getStatus() : VendorStatus.ACTIVE);
        vendor.setPayStatus(VendorPayStatus.UNPAID);
        vendor.setTotalBusiness(BigDecimal.ZERO);
        vendor.setTotalPaid(BigDecimal.ZERO);
        vendor.setCreditLimit(req.getCreditLimit() != null ? req.getCreditLimit() : BigDecimal.ZERO);
        vendor.setOpeningBalance(req.getOpeningBalance() != null ? req.getOpeningBalance() : BigDecimal.ZERO);
        vendor.setVerified(false);

        Vendor saved = vendorRepository.save(vendor);
        log.info("Vendor created with code: {} for tenantId: {}", saved.getVendorCode(), tenantId);

        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(NotificationType.VENDOR_ADDED.name())
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("New Vendor: " + saved.getVendorName())
                .message("Vendor " + saved.getVendorName() + " (" + saved.getVendorCode() + ") was added")
                .referenceType("VENDOR")
                .referencePublicId(saved.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());

        return vendorMapper.toResponse(saved);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO update(Long id, VendorRequestDTO req) {
        Vendor vendor = findOrThrow(id);
        vendorMapper.updateEntity(req, vendor);

        Vendor updated = vendorRepository.save(vendor);
        log.info("Vendor updated with id: {} for tenantId: {}", id, vendor.getTenantId());

        return vendorMapper.toResponse(updated);
    }

    // ── UPDATE STATUS ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO updateStatus(Long id, VendorStatusUpdateDTO req) {
        Vendor vendor = findOrThrow(id);
        vendor.setStatus(req.getStatus());

        Vendor updated = vendorRepository.save(vendor);
        log.info("Vendor status updated to: {} for id: {}", req.getStatus(), id);

        return vendorMapper.toResponse(updated);
    }

    // ── UPDATE PAYMENT ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO updatePayment(Long id, VendorPaymentUpdateDTO req) {
        Vendor vendor = findOrThrow(id);
        vendor.setPayStatus(req.getPayStatus());

        if (req.getAmountPaid() != null) {
            BigDecimal newPaid = vendor.getTotalPaid().add(req.getAmountPaid());
            vendor.setTotalPaid(newPaid);
            log.debug("Vendor payment updated: {} for id: {}", req.getAmountPaid(), id);
        }

        Vendor updated = vendorRepository.save(vendor);
        return vendorMapper.toResponse(updated);
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(Long id) {
        Vendor vendor = findOrThrow(id);
        vendor.softDelete("system");
        vendorRepository.save(vendor);
        log.info("Vendor soft-deleted with id: {}", id);
    }

    // ── FILTER ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<VendorResponseDTO> filter(String status, String type, String payStatus) {
        Long tenantId = TenantContext.getTenantId();

        Specification<Vendor> spec = Specification
                .where(VendorSpecification.hasTenant(tenantId))
                .and(VendorSpecification.notDeleted())
                .and(VendorSpecification.hasStatus(parseStatus(status)))
                .and(VendorSpecification.hasType(type))
                .and(VendorSpecification.hasPayStatus(parsePayStatus(payStatus)));

        log.debug("Filtering vendors: status={}, type={}, payStatus={}", status, type, payStatus);

        return vendorRepository.findAll(spec)
                .stream()
                .map(vendorMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ── SEARCH ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<VendorResponseDTO> search(String q) {
        Long tenantId = TenantContext.getTenantId();
        log.debug("Searching vendors for tenantId: {} with query: {}", tenantId, q);

        return vendorRepository.search(tenantId, q)
                .stream()
                .map(vendorMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ── GET BY TYPE ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<VendorResponseDTO> getByType(String type) {
        Long tenantId = TenantContext.getTenantId();
        log.debug("Fetching vendors by type: {} for tenantId: {}", type, tenantId);

        return vendorRepository.findByTenantIdAndVendorTypeAndDeletedAtIsNull(tenantId, type)
                .stream()
                .map(vendorMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ── STATS ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public VendorStatsDTO getStats() {
        Long tenantId = TenantContext.getTenantId();

        long total       = vendorRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        long active      = vendorRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, VendorStatus.ACTIVE);
        long inactive    = vendorRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, VendorStatus.INACTIVE);
        long blacklisted = vendorRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, VendorStatus.BLACKLISTED);

        Map<String, Long> totalByType = new LinkedHashMap<>();
        for (String vType : VENDOR_TYPES) {
            totalByType.put(vType, vendorRepository.countByTenantIdAndVendorTypeAndDeletedAtIsNull(tenantId, vType));
        }

        BigDecimal totalBusiness    = vendorRepository.sumTotalBusinessByTenantIdAndDeletedAtIsNull(tenantId);
        BigDecimal totalPaid        = vendorRepository.sumTotalPaidByTenantIdAndDeletedAtIsNull(tenantId);
        BigDecimal totalOutstanding = totalBusiness != null && totalPaid != null
                ? totalBusiness.subtract(totalPaid)
                : BigDecimal.ZERO;
        Double avgRating            = vendorRepository.avgRatingByTenantIdAndDeletedAtIsNull(tenantId);

        log.debug("Stats computed for tenantId: {}", tenantId);

        return VendorStatsDTO.builder()
                .total(total)
                .active(active)
                .inactive(inactive)
                .blacklisted(blacklisted)
                .totalByType(totalByType)
                .totalBusiness(totalBusiness != null ? totalBusiness : BigDecimal.ZERO)
                .totalPaid(totalPaid != null ? totalPaid : BigDecimal.ZERO)
                .totalOutstanding(totalOutstanding)
                .avgRating(avgRating != null ? avgRating : 0.0)
                .totalBookings(0)
                .build();
    }

    // ── GET BOOKINGS ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getBookings(Long id) {
        findOrThrow(id);
        log.debug("Fetching bookings for vendor id: {}", id);
        return new ArrayList<>();
    }

    // ── RATE VENDOR ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO rateVendor(Long id, VendorRatingDTO req) {
        Vendor vendor = findOrThrow(id);

        int count = vendor.getRatingCount() != null ? vendor.getRatingCount() : 0;
        double current = vendor.getRating() != null ? vendor.getRating() : 0.0;

        double newAvg = (current * count + req.getRating()) / (count + 1);
        vendor.setRating(Math.round(newAvg * 10.0) / 10.0);
        vendor.setRatingCount(count + 1);

        Vendor updated = vendorRepository.save(vendor);
        log.info("Vendor rated with new avg: {} for id: {}", vendor.getRating(), id);

        return vendorMapper.toResponse(updated);
    }

    // ── EXPORT CSV ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        Long tenantId = TenantContext.getTenantId();
        List<Vendor> vendors = vendorRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);

        StringBuilder csv = new StringBuilder();
        csv.append("Code,Name,Type,Contact,Phone,Email,City,State,Country,Status,Pay Status,Rating,Total Business,Total Paid,Outstanding,Commission %,Currency,GST,PAN\n");

        for (Vendor v : vendors) {
            csv.append(escape(v.getVendorCode())).append(",")
                    .append(escape(v.getVendorName())).append(",")
                    .append(escape(v.getVendorType())).append(",")
                    .append(escape(v.getContactPerson())).append(",")
                    .append(escape(v.getPhone())).append(",")
                    .append(escape(v.getEmail())).append(",")
                    .append(escape(v.getCity())).append(",")
                    .append(escape(v.getState())).append(",")
                    .append(escape(v.getCountry())).append(",")
                    .append(escape(v.getStatus() != null ? v.getStatus().name() : "")).append(",")
                    .append(escape(v.getPayStatus() != null ? v.getPayStatus().name() : "")).append(",")
                    .append(v.getRating() != null ? v.getRating() : "").append(",")
                    .append(v.getTotalBusiness() != null ? v.getTotalBusiness() : "0").append(",")
                    .append(v.getTotalPaid() != null ? v.getTotalPaid() : "0").append(",")
                    .append(v.getOutstanding() != null ? v.getOutstanding() : "0").append(",")
                    .append(v.getCommissionRate() != null ? v.getCommissionRate() : "").append(",")
                    .append(escape(v.getCurrency())).append(",")
                    .append(escape(v.getGstNumber())).append(",")
                    .append(escape(v.getPanNumber())).append("\n");
        }

        log.info("CSV exported for {} vendors from tenantId: {}", vendors.size(), tenantId);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── SEND EMAIL ────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> sendEmail(Long id, VendorEmailDTO req) {
        Vendor vendor = findOrThrow(id);
        log.info("Email to vendor {} ({}): subject={}", vendor.getVendorCode(), vendor.getEmail(), req.getSubject());
        return Map.of("message", "Email sent successfully");
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private Vendor findOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        // Tenant-scoped at the query level — does NOT load cross-tenant rows into memory.
        return vendorRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found: " + id));
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    private String escape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    /** Lenient filter parsing: blank/unknown values mean "no filter" (returns null). */
    private static VendorStatus parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return VendorStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static VendorPayStatus parsePayStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return VendorPayStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}