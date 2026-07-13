package com.crm.travelcrm.company.service;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.company.dto.AiCreditsDTO;
import com.crm.travelcrm.company.dto.CompanyDTO;
import com.crm.travelcrm.company.dto.CompanyUpdateRequest;
import com.crm.travelcrm.company.dto.SubscriptionDTO;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CompanyService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);

    /** Historical "unlimited" sentinel used for {@code Tenant.maxUsers} (see TenantServiceImpl.changePlan). */
    private static final int UNLIMITED_USERS_SENTINEL = 1_000_000;

    private final CompanyRepository companyRepository;
    private final CloudinaryService cloudinaryService;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;

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

    // Real subscription snapshot from the tenant's plan + billing state.
    @Transactional(readOnly = true)
    public SubscriptionDTO getSubscription(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            // Defensive: an authenticated tenant user should always resolve.
            return SubscriptionDTO.builder().plan("—").status("UNKNOWN").features(List.of()).build();
        }
        Plan plan = planRepository.findByCode(tenant.getPlan()).orElse(null);

        LocalDate start = tenant.getSubscriptionStartDate();
        LocalDate end = tenant.getSubscriptionEndDate();
        Integer daysLeft = end != null
                ? (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), end))
                : null;

        // Effective modules: the tenant's own overrides, else the plan's defaults.
        List<String> features = !tenant.getEnabledModules().isEmpty()
                ? new ArrayList<>(tenant.getEnabledModules())
                : (plan != null ? new ArrayList<>(plan.getModules()) : List.of());

        return SubscriptionDTO.builder()
                .plan(plan != null ? plan.getDisplayName() : tenant.getPlan().name())
                .planCode(tenant.getPlan().name())
                .monthlyPrice(plan != null ? plan.getMonthlyPrice() : null)
                .currency(plan != null ? plan.getCurrency() : null)
                .startDate(start != null ? start.format(DATE_FMT) : null)
                .endDate(end != null ? end.format(DATE_FMT) : null)
                .status(tenant.getStatus().name())
                .daysLeft(daysLeft)
                .pastDueSince(tenant.getPastDueSince() != null ? tenant.getPastDueSince().format(DATE_FMT) : null)
                .features(features)
                .maxUsers(normalizeUnlimited(tenant.getMaxUsers()))
                .maxLeads(tenant.getMaxLeads())
                .maxBookingsPerMonth(tenant.getMaxBookingsPerMonth())
                .maxStorageMb(tenant.getMaxStorageMb())
                .build();
    }

    /** Normalises the stored user cap to {@code null}=unlimited (null, ≤0, or the unlimited sentinel). */
    private static Integer normalizeUnlimited(Integer raw) {
        return (raw == null || raw <= 0 || raw >= UNLIMITED_USERS_SENTINEL) ? null : raw;
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