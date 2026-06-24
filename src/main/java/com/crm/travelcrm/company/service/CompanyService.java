package com.crm.travelcrm.company.service;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.company.dto.AiCreditsDTO;
import com.crm.travelcrm.company.dto.CompanyDTO;
import com.crm.travelcrm.company.dto.CompanyUpdateRequest;
import com.crm.travelcrm.company.dto.SubscriptionDTO;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);

    private final CompanyRepository companyRepository;
    private final CloudinaryService cloudinaryService;

    @Transactional
    public CompanyDTO get(Long tenantId) {
        return toDto(loadOrCreate(tenantId));
    }

    @Transactional
    public CompanyDTO update(CompanyUpdateRequest req, Long tenantId) {
        Company c = loadOrCreate(tenantId);
        c.setName(req.getName().trim());
        c.setPrefix(req.getPrefix());
        c.setEmail(req.getEmail().trim().toLowerCase());
        c.setPhone(req.getPhone());
        c.setWebsite(req.getWebsite());
        c.setOperatingSince(req.getOperatingSince());
        c.setTotalReviews(req.getTotalReviews());
        c.setTripsSold(req.getTripsSold());
        c.setGstin(req.getGstin());
        c.setTan(req.getTan());
        c.setAddress(req.getAddress());
        c.setState(req.getState());
        if (c.getStatus() == null) {
            c.setStatus("Active");
        }
        return toDto(companyRepository.save(c));
    }

    @Transactional
    public CompanyDTO uploadLogo(MultipartFile file, Long tenantId) {
        Company c = loadOrCreate(tenantId);
        c.setLogoUrl(cloudinaryService.uploadImage(file, "company/logos"));
        return toDto(companyRepository.save(c));
    }

    @Transactional
    public CompanyDTO uploadFavicon(MultipartFile file, Long tenantId) {
        Company c = loadOrCreate(tenantId);
        c.setFaviconUrl(cloudinaryService.uploadImage(file, "company/favicons"));
        return toDto(companyRepository.save(c));
    }

    // Placeholder until a real billing/subscription source exists.
    @Transactional(readOnly = true)
    public SubscriptionDTO getSubscription(Long tenantId) {
        LocalDate start = companyRepository.findByTenantId(tenantId)
                .filter(c -> c.getCreatedAt() != null)
                .map(c -> c.getCreatedAt().toLocalDate())
                .orElse(LocalDate.now());
        LocalDate end = start.plusDays(30);
        int daysLeft = (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), end));
        return SubscriptionDTO.builder()
                .plan("Standard Plan")
                .startDate(start.format(DATE_FMT))
                .endDate(end.format(DATE_FMT))
                .status("Active")
                .daysLeft(daysLeft)
                .features(List.of("All Core Features"))
                .build();
    }

    // Placeholder until AI-credit metering exists.
    @Transactional(readOnly = true)
    public AiCreditsDTO getAiCredits(Long tenantId) {
        return AiCreditsDTO.builder().used(0).total(10).usedCost(0.0).build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Exactly one company row per tenant; lazily created on first access.
    private Company loadOrCreate(Long tenantId) {
        return companyRepository.findByTenantId(tenantId)
                .orElseGet(() -> companyRepository.save(
                        Company.builder()
                                .status("Active")
                                .totalReviews(0)
                                .tripsSold(0)
                                .build()));
    }

    private CompanyDTO toDto(Company c) {
        return CompanyDTO.builder()
                .publicId(c.getPublicId())
                .name(c.getName())
                .prefix(c.getPrefix())
                .email(c.getEmail())
                .phone(c.getPhone())
                .website(c.getWebsite())
                .operatingSince(c.getOperatingSince())
                .totalReviews(c.getTotalReviews())
                .tripsSold(c.getTripsSold())
                .gstin(c.getGstin())
                .tan(c.getTan())
                .status(c.getStatus())
                .address(c.getAddress())
                .state(c.getState())
                .logoUrl(c.getLogoUrl())
                .faviconUrl(c.getFaviconUrl())
                .createdDate(c.getCreatedAt() != null ? c.getCreatedAt().format(DATE_FMT) : null)
                .build();
    }
}