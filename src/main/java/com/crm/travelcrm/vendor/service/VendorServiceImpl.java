package com.crm.travelcrm.vendor.service;

import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.vendor.dto.request.*;
import com.crm.travelcrm.vendor.dto.response.VendorResponseDTO;
import com.crm.travelcrm.vendor.dto.response.VendorStatsDTO;
import com.crm.travelcrm.vendor.entity.VendorEntity;
import com.crm.travelcrm.vendor.repository.VendorRepository;
import com.crm.travelcrm.vendor.specification.VendorSpecification;
import com.crm.travelcrm.vendor.util.VendorCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // ── GET ALL ───────────────────────────────────────────────────────────────

    @Override
    public List<VendorResponseDTO> getAll() {
        Long tenantId = TenantContext.getTenantId();
        return vendorRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── GET BY ID ─────────────────────────────────────────────────────────────

    @Override
    public VendorResponseDTO getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    // ── GET BY CODE ───────────────────────────────────────────────────────────

    @Override
    public VendorResponseDTO getByCode(String code) {
        Long tenantId = TenantContext.getTenantId();
        VendorEntity vendor = vendorRepository.findByVendorCodeAndTenantId(code, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found with code: " + code));
        return toResponse(vendor);
    }

    // ── CREATE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO create(VendorRequestDTO req) {
        VendorEntity vendor = VendorEntity.builder()
                .vendorCode(vendorCodeGenerator.generate())
                .vendorName(req.getVendorName())
                .vendorType(req.getVendorType())
                .contactPerson(req.getContactPerson())
                .phone(req.getPhone())
                .alternatePhone(req.getAlternatePhone())
                .email(req.getEmail())
                .whatsapp(req.getWhatsapp())
                .contractType(req.getContractType())
                .paymentTerms(req.getPaymentTerms())
                .commPref(req.getCommPref())
                .status(req.getStatus() != null ? req.getStatus() : "Active")
                .payStatus("Unpaid")
                .city(req.getCity())
                .state(req.getState())
                .country(req.getCountry())
                .address(req.getAddress())
                .pincode(req.getPincode())
                .coverageAreas(req.getCoverageAreas())
                .services(req.getServices() != null ? req.getServices() : new ArrayList<>())
                .serviceDescription(req.getServiceDescription())
                .commissionRate(req.getCommissionRate())
                .currency(req.getCurrency())
                .creditPeriod(req.getCreditPeriod())
                .creditLimit(req.getCreditLimit() != null ? req.getCreditLimit() : BigDecimal.ZERO)
                .openingBalance(req.getOpeningBalance() != null ? req.getOpeningBalance() : BigDecimal.ZERO)
                .totalBusiness(BigDecimal.ZERO)
                .totalPaid(BigDecimal.ZERO)
                .bankName(req.getBankName())
                .accountName(req.getAccountName())
                .accountNumber(req.getAccountNumber())
                .ifscCode(req.getIfscCode())
                .upiId(req.getUpiId())
                .gstNumber(req.getGstNumber())
                .panNumber(req.getPanNumber())
                .notes(req.getNotes())
                .specialConditions(req.getSpecialConditions())
                .verified(false)
                .build();

        return toResponse(vendorRepository.save(vendor));
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO update(Long id, VendorRequestDTO req) {
        VendorEntity vendor = findOrThrow(id);

        vendor.setVendorName(req.getVendorName());
        vendor.setVendorType(req.getVendorType());
        vendor.setContactPerson(req.getContactPerson());
        vendor.setPhone(req.getPhone());
        vendor.setAlternatePhone(req.getAlternatePhone());
        vendor.setEmail(req.getEmail());
        vendor.setWhatsapp(req.getWhatsapp());
        vendor.setContractType(req.getContractType());
        vendor.setPaymentTerms(req.getPaymentTerms());
        vendor.setCommPref(req.getCommPref());
        if (req.getStatus() != null) vendor.setStatus(req.getStatus());
        vendor.setCity(req.getCity());
        vendor.setState(req.getState());
        vendor.setCountry(req.getCountry());
        vendor.setAddress(req.getAddress());
        vendor.setPincode(req.getPincode());
        vendor.setCoverageAreas(req.getCoverageAreas());
        if (req.getServices() != null) vendor.setServices(req.getServices());
        vendor.setServiceDescription(req.getServiceDescription());
        vendor.setCommissionRate(req.getCommissionRate());
        vendor.setCurrency(req.getCurrency());
        vendor.setCreditPeriod(req.getCreditPeriod());
        if (req.getCreditLimit() != null) vendor.setCreditLimit(req.getCreditLimit());
        if (req.getOpeningBalance() != null) vendor.setOpeningBalance(req.getOpeningBalance());
        vendor.setBankName(req.getBankName());
        vendor.setAccountName(req.getAccountName());
        vendor.setAccountNumber(req.getAccountNumber());
        vendor.setIfscCode(req.getIfscCode());
        vendor.setUpiId(req.getUpiId());
        vendor.setGstNumber(req.getGstNumber());
        vendor.setPanNumber(req.getPanNumber());
        vendor.setNotes(req.getNotes());
        vendor.setSpecialConditions(req.getSpecialConditions());

        return toResponse(vendorRepository.save(vendor));
    }

    // ── UPDATE STATUS ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO updateStatus(Long id, VendorStatusUpdateDTO req) {
        VendorEntity vendor = findOrThrow(id);
        vendor.setStatus(req.getStatus());
        return toResponse(vendorRepository.save(vendor));
    }

    // ── UPDATE PAYMENT ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO updatePayment(Long id, VendorPaymentUpdateDTO req) {
        VendorEntity vendor = findOrThrow(id);
        vendor.setPayStatus(req.getPayStatus());
        if (req.getAmountPaid() != null) {
            BigDecimal newPaid = vendor.getTotalPaid().add(req.getAmountPaid());
            vendor.setTotalPaid(newPaid);
        }
        return toResponse(vendorRepository.save(vendor));
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(Long id) {
        VendorEntity vendor = findOrThrow(id);
        vendor.softDelete("system");
        vendorRepository.save(vendor);
    }

    // ── FILTER ────────────────────────────────────────────────────────────────

    @Override
    public List<VendorResponseDTO> filter(String status, String type, String payStatus) {
        Long tenantId = TenantContext.getTenantId();
        Specification<VendorEntity> spec = Specification
                .where(VendorSpecification.hasTenant(tenantId))
                .and(VendorSpecification.notDeleted())
                .and(VendorSpecification.hasStatus(status))
                .and(VendorSpecification.hasType(type))
                .and(VendorSpecification.hasPayStatus(payStatus));

        return vendorRepository.findAll(spec).stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── SEARCH ────────────────────────────────────────────────────────────────

    @Override
    public List<VendorResponseDTO> search(String q) {
        Long tenantId = TenantContext.getTenantId();
        return vendorRepository.search(tenantId, q)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── GET BY TYPE ───────────────────────────────────────────────────────────

    @Override
    public List<VendorResponseDTO> getByType(String type) {
        Long tenantId = TenantContext.getTenantId();
        return vendorRepository.findByTenantIdAndVendorType(tenantId, type)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ── STATS ─────────────────────────────────────────────────────────────────

    @Override
    public VendorStatsDTO getStats() {
        Long tenantId = TenantContext.getTenantId();

        long total       = vendorRepository.countByTenantId(tenantId);
        long active      = vendorRepository.countByTenantIdAndStatus(tenantId, "Active");
        long inactive    = vendorRepository.countByTenantIdAndStatus(tenantId, "Inactive");
        long blacklisted = vendorRepository.countByTenantIdAndStatus(tenantId, "Blacklisted");

        Map<String, Long> totalByType = new LinkedHashMap<>();
        for (String vType : VENDOR_TYPES) {
            totalByType.put(vType, vendorRepository.countByTenantIdAndVendorType(tenantId, vType));
        }

        BigDecimal totalBusiness    = vendorRepository.sumTotalBusinessByTenantId(tenantId);
        BigDecimal totalPaid        = vendorRepository.sumTotalPaidByTenantId(tenantId);
        BigDecimal totalOutstanding = totalBusiness.subtract(totalPaid);
        Double avgRating            = vendorRepository.avgRatingByTenantId(tenantId);

        return VendorStatsDTO.builder()
                .total(total)
                .active(active)
                .inactive(inactive)
                .blacklisted(blacklisted)
                .totalByType(totalByType)
                .totalBusiness(totalBusiness)
                .totalPaid(totalPaid)
                .totalOutstanding(totalOutstanding)
                .avgRating(avgRating != null ? avgRating : 0.0)
                .totalBookings(0)
                .build();
    }

    // ── GET BOOKINGS ──────────────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> getBookings(Long id) {
        findOrThrow(id);
        return new ArrayList<>();
    }

    // ── RATE VENDOR ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public VendorResponseDTO rateVendor(Long id, VendorRatingDTO req) {
        VendorEntity vendor = findOrThrow(id);

        int count = vendor.getRatingCount() != null ? vendor.getRatingCount() : 0;
        double current = vendor.getRating() != null ? vendor.getRating() : 0.0;

        double newAvg = (current * count + req.getRating()) / (count + 1);
        vendor.setRating(Math.round(newAvg * 10.0) / 10.0);
        vendor.setRatingCount(count + 1);

        return toResponse(vendorRepository.save(vendor));
    }

    // ── EXPORT CSV ────────────────────────────────────────────────────────────

    @Override
    public byte[] exportCsv() {
        Long tenantId = TenantContext.getTenantId();
        List<VendorEntity> vendors = vendorRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId);

        StringBuilder csv = new StringBuilder();
        csv.append("Code,Name,Type,Contact,Phone,Email,City,State,Country,Status,Pay Status,Rating,Total Business,Total Paid,Outstanding,Commission %,Currency,GST,PAN\n");

        for (VendorEntity v : vendors) {
            csv.append(escape(v.getVendorCode())).append(",")
               .append(escape(v.getVendorName())).append(",")
               .append(escape(v.getVendorType())).append(",")
               .append(escape(v.getContactPerson())).append(",")
               .append(escape(v.getPhone())).append(",")
               .append(escape(v.getEmail())).append(",")
               .append(escape(v.getCity())).append(",")
               .append(escape(v.getState())).append(",")
               .append(escape(v.getCountry())).append(",")
               .append(escape(v.getStatus())).append(",")
               .append(escape(v.getPayStatus())).append(",")
               .append(v.getRating() != null ? v.getRating() : "").append(",")
               .append(v.getTotalBusiness() != null ? v.getTotalBusiness() : "0").append(",")
               .append(v.getTotalPaid() != null ? v.getTotalPaid() : "0").append(",")
               .append(v.getOutstanding()).append(",")
               .append(v.getCommissionRate() != null ? v.getCommissionRate() : "").append(",")
               .append(escape(v.getCurrency())).append(",")
               .append(escape(v.getGstNumber())).append(",")
               .append(escape(v.getPanNumber())).append("\n");
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── SEND EMAIL ────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> sendEmail(Long id, VendorEmailDTO req) {
        VendorEntity vendor = findOrThrow(id);
        log.info("Email to vendor {} ({}): subject={}", vendor.getVendorCode(), vendor.getEmail(), req.getSubject());
        return Map.of("message", "Email sent successfully");
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private VendorEntity findOrThrow(Long id) {
        Long tenantId = TenantContext.getTenantId();
        return vendorRepository.findById(id)
                .filter(v -> v.getTenantId().equals(tenantId) && v.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor not found: " + id));
    }

    private VendorResponseDTO toResponse(VendorEntity v) {
        return VendorResponseDTO.builder()
                .id(v.getId())
                .vendorCode(v.getVendorCode())
                .vendorName(v.getVendorName())
                .vendorType(v.getVendorType())
                .contactPerson(v.getContactPerson())
                .phone(v.getPhone())
                .alternatePhone(v.getAlternatePhone())
                .email(v.getEmail())
                .whatsapp(v.getWhatsapp())
                .contractType(v.getContractType())
                .paymentTerms(v.getPaymentTerms())
                .commPref(v.getCommPref())
                .status(v.getStatus())
                .payStatus(v.getPayStatus())
                .city(v.getCity())
                .state(v.getState())
                .country(v.getCountry())
                .address(v.getAddress())
                .pincode(v.getPincode())
                .coverageAreas(v.getCoverageAreas())
                .services(v.getServices())
                .serviceDescription(v.getServiceDescription())
                .commissionRate(v.getCommissionRate())
                .currency(v.getCurrency())
                .creditPeriod(v.getCreditPeriod())
                .creditLimit(v.getCreditLimit())
                .openingBalance(v.getOpeningBalance())
                .totalBusiness(v.getTotalBusiness())
                .totalPaid(v.getTotalPaid())
                .outstanding(v.getOutstanding())
                .bankName(v.getBankName())
                .accountName(v.getAccountName())
                .accountNumber(v.getAccountNumber())
                .ifscCode(v.getIfscCode())
                .upiId(v.getUpiId())
                .gstNumber(v.getGstNumber())
                .panNumber(v.getPanNumber())
                .rating(v.getRating())
                .ratingCount(v.getRatingCount())
                .verified(v.getVerified())
                .notes(v.getNotes())
                .specialConditions(v.getSpecialConditions())
                .joinDate(v.getCreatedAt())
                .build();
    }

    private String escape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}